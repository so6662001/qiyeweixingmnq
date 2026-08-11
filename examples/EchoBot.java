import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 示例：用 Java HttpClient 轮询模拟器消息并文字回复。
 *
 * <pre>
 *   # 先启动模拟器后执行：
 *   javac examples/EchoBot.java && java -cp examples EchoBot
 * </pre>
 */
public class EchoBot {

    private static final String BASE = "http://127.0.0.1:8000";
    private static final Pattern MSGID = Pattern.compile("\"msgid\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MSGTYPE = Pattern.compile("\"msgtype\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CONTENT = Pattern.compile("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern FROM = Pattern.compile("\"from_user\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern AGENT = Pattern.compile("\"agent_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MEDIA = Pattern.compile("\"media_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern RECOG = Pattern.compile("\"recognition\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        put(client, "/api/config/demo-bot", "{\"enabled\":false}");
        System.out.println("EchoBot 已启动，轮询 /api/messages …");

        String last = null;
        while (true) {
            String path = "/api/messages?role=user" + (last == null ? "" : "&after=" + last);
            String body = get(client, path);
            // 粗解析数组中的对象
            int idx = 0;
            while (true) {
                int start = body.indexOf("{\"msgid\"", idx);
                if (start < 0) {
                    start = body.indexOf("{\"msgid\":", idx);
                }
                if (start < 0) {
                    break;
                }
                int end = body.indexOf("},{", start);
                if (end < 0) {
                    end = body.lastIndexOf('}');
                }
                String obj = body.substring(start, end + 1);
                idx = start + 1;

                String msgid = first(MSGID, obj);
                String msgtype = first(MSGTYPE, obj);
                String fromUser = first(FROM, obj);
                String agentId = first(AGENT, obj);
                if (msgid == null) {
                    continue;
                }
                last = msgid;

                String reply;
                if ("text".equals(msgtype)) {
                    reply = "echo: " + unescape(first(CONTENT, obj));
                } else {
                    reply = "收到语音 " + first(MEDIA, obj) + " / " + unescape(first(RECOG, obj));
                }
                String payload = """
                        {"content":"%s","to_user":"%s","agent_id":"%s","reply_to_msgid":"%s"}
                        """.formatted(escape(reply), fromUser, agentId, msgid).trim();
                post(client, "/api/reply/text", payload);
                System.out.println("replied: " + reply);
            }
            Thread.sleep(1000);
        }
    }

    private static String get(HttpClient client, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static void post(HttpClient client, String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static void put(HttpClient client, String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String unescape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
