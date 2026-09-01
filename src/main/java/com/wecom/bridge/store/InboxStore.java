package com.wecom.bridge.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wecom.bridge.config.BridgeProperties;
import com.wecom.bridge.model.InboxChannel;
import com.wecom.bridge.model.InboxConversation;
import com.wecom.bridge.model.InboxMessage;
import com.wecom.bridge.model.MessageDirection;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话与消息存储：内存索引 + JSON 快照落盘。
 *
 * <p>刻意不引入数据库依赖，单机部署即可用；如需多副本再替换本类实现。</p>
 */
@Component
public class InboxStore {

    private static final Logger log = LoggerFactory.getLogger(InboxStore.class);
    private static final String SNAPSHOT_FILE = "inbox.json";

    private final BridgeProperties properties;
    private final ObjectMapper objectMapper;

    private final Map<String, InboxConversation> conversations = new ConcurrentHashMap<>();
    private final Map<String, List<InboxMessage>> messagesByConversation = new ConcurrentHashMap<>();
    private final Map<String, String> cursors = new ConcurrentHashMap<>();

    /** 官方 msgid 去重表，按插入顺序淘汰。 */
    private final Map<String, Boolean> seenMessageIds;

    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private ScheduledExecutorService flusher;

    public InboxStore(BridgeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        int dedupeCapacity = Math.max(properties.getMaxMessages() * 2, 2000);
        this.seenMessageIds = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > dedupeCapacity;
            }
        });
    }

    @PostConstruct
    void start() {
        load();
        flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "inbox-store-flusher");
            thread.setDaemon(true);
            return thread;
        });
        flusher.scheduleWithFixedDelay(this::flushIfDirty, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (flusher != null) {
            flusher.shutdown();
        }
        flushIfDirty();
    }

    /**
     * 官方消息去重：同一 msgid 只入库一次（回调重试、游标重放都会重复推送）。
     *
     * @return true 表示首次出现
     */
    public boolean markSeen(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return true;
        }
        return seenMessageIds.put(messageId, Boolean.TRUE) == null;
    }

    public InboxConversation upsertConversation(InboxChannel channel, String peerId, String peerName, String openKfid) {
        String id = InboxConversation.buildId(channel, peerId);
        return conversations.compute(id, (key, existing) -> {
            InboxConversation conversation = existing;
            if (conversation == null) {
                conversation = new InboxConversation();
                conversation.setId(key);
                conversation.setChannel(channel);
                conversation.setPeerId(peerId);
            }
            if (peerName != null && !peerName.isBlank()) {
                conversation.setPeerName(peerName);
            }
            if (openKfid != null && !openKfid.isBlank()) {
                conversation.setOpenKfid(openKfid);
            }
            return conversation;
        });
    }

    public void append(InboxMessage message) {
        String conversationId = message.getConversationId();
        List<InboxMessage> list = messagesByConversation
                .computeIfAbsent(conversationId, key -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            list.add(message);
            list.sort(Comparator.comparingLong(InboxMessage::getCreateTime));
        }

        InboxConversation conversation = conversations.get(conversationId);
        if (conversation != null) {
            if (message.getCreateTime() >= conversation.getLastMessageAt()) {
                conversation.setLastMessageAt(message.getCreateTime());
                conversation.setLastPreview(message.preview());
            }
            if (message.getDirection() == MessageDirection.INBOUND) {
                conversation.setLastInboundAt(Math.max(conversation.getLastInboundAt(), message.getCreateTime()));
                conversation.setUnread(conversation.getUnread() + 1);
            }
        }
        trim();
        dirty.set(true);
    }

    /** 消息对象按引用存储，改完字段后调用本方法触发落盘。 */
    public void markDirty() {
        dirty.set(true);
    }

    public List<InboxConversation> listConversations() {
        List<InboxConversation> list = new ArrayList<>(conversations.values());
        list.sort(Comparator.comparingLong(InboxConversation::getLastMessageAt).reversed());
        return list;
    }

    public Optional<InboxConversation> findConversation(String conversationId) {
        return Optional.ofNullable(conversations.get(conversationId));
    }

    public List<InboxMessage> listMessages(String conversationId, int limit) {
        List<InboxMessage> list = messagesByConversation.get(conversationId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            int size = list.size();
            if (limit <= 0 || limit >= size) {
                return new ArrayList<>(list);
            }
            return new ArrayList<>(list.subList(size - limit, size));
        }
    }

    public void markRead(String conversationId) {
        InboxConversation conversation = conversations.get(conversationId);
        if (conversation != null && conversation.getUnread() != 0) {
            conversation.setUnread(0);
            dirty.set(true);
        }
    }

    public int totalUnread() {
        return conversations.values().stream().mapToInt(InboxConversation::getUnread).sum();
    }

    public String cursor(String openKfid) {
        return cursors.getOrDefault(openKfid, "");
    }

    public void saveCursor(String openKfid, String cursor) {
        if (openKfid == null || openKfid.isBlank() || cursor == null || cursor.isBlank()) {
            return;
        }
        cursors.put(openKfid, cursor);
        dirty.set(true);
    }

    public void clear() {
        conversations.clear();
        messagesByConversation.clear();
        cursors.clear();
        seenMessageIds.clear();
        dirty.set(true);
        flushIfDirty();
    }

    private void trim() {
        int max = properties.getMaxMessages();
        int total = messagesByConversation.values().stream().mapToInt(List::size).sum();
        if (total <= max) {
            return;
        }
        List<InboxMessage> all = new ArrayList<>();
        messagesByConversation.values().forEach(list -> {
            synchronized (list) {
                all.addAll(list);
            }
        });
        all.sort(Comparator.comparingLong(InboxMessage::getCreateTime));
        int toRemove = total - max;
        for (int i = 0; i < toRemove && i < all.size(); i++) {
            InboxMessage message = all.get(i);
            List<InboxMessage> list = messagesByConversation.get(message.getConversationId());
            if (list != null) {
                synchronized (list) {
                    list.remove(message);
                }
            }
        }
    }

    private Path snapshotPath() {
        return Path.of(properties.getDataDir(), SNAPSHOT_FILE);
    }

    private void load() {
        Path path = snapshotPath();
        if (!Files.exists(path)) {
            return;
        }
        try {
            InboxSnapshot snapshot = objectMapper.readValue(Files.readAllBytes(path), InboxSnapshot.class);
            snapshot.getConversations().forEach(conversation -> {
                if (conversation.getId() != null) {
                    conversations.put(conversation.getId(), conversation);
                }
            });
            snapshot.getMessages().forEach(message -> {
                if (message.getConversationId() == null) {
                    return;
                }
                messagesByConversation
                        .computeIfAbsent(message.getConversationId(), key -> Collections.synchronizedList(new ArrayList<>()))
                        .add(message);
                markSeen(message.getId());
            });
            cursors.putAll(snapshot.getCursors());
            log.info("已从 {} 恢复 {} 个会话、{} 条消息", path, conversations.size(), snapshot.getMessages().size());
        } catch (IOException e) {
            log.warn("恢复收件箱快照失败，将以空状态启动: {}", e.getMessage());
        }
    }

    private void flushIfDirty() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        writeLock.lock();
        try {
            InboxSnapshot snapshot = new InboxSnapshot();
            snapshot.setConversations(new ArrayList<>(conversations.values()));
            List<InboxMessage> all = new ArrayList<>();
            messagesByConversation.values().forEach(list -> {
                synchronized (list) {
                    all.addAll(list);
                }
            });
            all.sort(Comparator.comparingLong(InboxMessage::getCreateTime));
            snapshot.setMessages(all);
            snapshot.setCursors(new LinkedHashMap<>(cursors));

            Path path = snapshotPath();
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(SNAPSHOT_FILE + ".tmp");
            Files.write(temp, objectMapper.writeValueAsBytes(snapshot));
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            dirty.set(true);
            log.warn("写入收件箱快照失败: {}", e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }
}
