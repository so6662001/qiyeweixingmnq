package com.wecom.bridge.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 解析会话存档的消息明文 JSON。
 *
 * <p>通用字段：{@code msgid / action / from / tolist / roomid / msgtime / msgtype}，
 * 不同 msgtype 各带一个同名子对象。</p>
 */
public final class ArchiveMessageParser {

    private ArchiveMessageParser() {
    }

    public static ParsedArchiveMessage parse(ObjectMapper objectMapper, String plainJson) {
        JsonNode node;
        try {
            node = objectMapper.readTree(plainJson);
        } catch (IOException e) {
            throw new ArchiveSdkException("解析存档消息明文失败", e);
        }

        String msgtype = node.path("msgtype").asText("");
        Content content = extractContent(node, msgtype);

        List<String> tolist = new ArrayList<>();
        JsonNode toNode = node.path("tolist");
        if (toNode.isArray()) {
            toNode.forEach(item -> {
                String value = item.asText("");
                if (!value.isBlank()) {
                    tolist.add(value);
                }
            });
        }

        return new ParsedArchiveMessage(
                node.path("msgid").asText(""),
                node.path("action").asText("send"),
                node.path("from").asText(""),
                List.copyOf(tolist),
                node.path("roomid").asText(""),
                node.path("msgtime").asLong(0L),
                msgtype.isBlank() ? "text" : msgtype,
                content.text(),
                content.mediaId()
        );
    }

    private static Content extractContent(JsonNode node, String msgtype) {
        return switch (msgtype) {
            case "text" -> new Content(node.path("text").path("content").asText(""), null);
            case "image" -> new Content("[图片]", sdkFileId(node, "image"));
            case "voice" -> new Content(
                    "[语音] " + node.path("voice").path("play_length").asInt(0) + " 秒", sdkFileId(node, "voice"));
            case "video" -> new Content(
                    "[视频] " + node.path("video").path("play_length").asInt(0) + " 秒", sdkFileId(node, "video"));
            case "file" -> new Content(
                    "[文件] " + node.path("file").path("filename").asText(""), sdkFileId(node, "file"));
            case "emotion" -> new Content("[表情]", sdkFileId(node, "emotion"));
            case "link" -> new Content("[链接] "
                    + node.path("link").path("title").asText("") + " "
                    + node.path("link").path("link_url").asText(""), null);
            case "location" -> new Content("[位置] "
                    + node.path("location").path("title").asText("") + " "
                    + node.path("location").path("address").asText(""), null);
            case "card" -> new Content("[名片] "
                    + node.path("card").path("corpname").asText("") + " "
                    + node.path("card").path("userid").asText(""), null);
            case "weapp" -> new Content("[小程序] " + node.path("weapp").path("title").asText(""), null);
            case "chatrecord" -> new Content("[聊天记录] "
                    + node.path("chatrecord").path("title").asText(""), null);
            case "revoke" -> new Content("[撤回消息] 原 msgid "
                    + node.path("revoke").path("pre_msgid").asText(""), null);
            case "agree" -> new Content("客户同意会话存档", null);
            case "disagree" -> new Content("客户不同意会话存档", null);
            case "redpacket" -> new Content("[红包] " + node.path("redpacket").path("wish").asText(""), null);
            case "todo" -> new Content("[待办] " + node.path("todo").path("title").asText(""), null);
            case "vote" -> new Content("[投票] " + node.path("vote").path("votetitle").asText(""), null);
            case "collect" -> new Content("[填表] " + node.path("collect").path("title").asText(""), null);
            case "meeting" -> new Content("[会议邀请] " + node.path("meeting").path("topic").asText(""), null);
            case "switch" -> new Content("成员切换企业日志", null);
            default -> new Content("[" + msgtype + "]", null);
        };
    }

    private static String sdkFileId(JsonNode node, String field) {
        String value = node.path(field).path("sdkfileid").asText("");
        return value.isBlank() ? null : value;
    }

    private record Content(String text, String mediaId) {
    }

    /**
     * 归一化后的存档消息。
     */
    public record ParsedArchiveMessage(
            String msgid,
            String action,
            String from,
            List<String> tolist,
            String roomId,
            long msgtime,
            String msgtype,
            String content,
            String mediaId
    ) {

        public boolean isRoomChat() {
            return roomId != null && !roomId.isBlank();
        }

        /** msgid 以 _external 结尾表示这是一条外部消息。 */
        public boolean externalMessage() {
            return msgid != null && msgid.endsWith("_external");
        }

        public String firstTo() {
            return tolist.isEmpty() ? "" : tolist.get(0);
        }
    }
}
