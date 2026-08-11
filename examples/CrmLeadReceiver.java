import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * 本地 CRM 线索接口示例：接收朋友圈动态写入的线索。
 *
 * <pre>
 *   javac examples/CrmLeadReceiver.java && java -cp examples CrmLeadReceiver
 *   # 然后在模拟器配置 CRM URL: http://127.0.0.1:9100/crm/leads
 * </pre>
 */
public class CrmLeadReceiver {

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9100;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/crm/leads", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String json = new String(body, StandardCharsets.UTF_8);
            System.out.println("==== CRM Lead ====");
            System.out.println(json);
            byte[] resp = "{\"ok\":true,\"stored\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        System.out.println("CRM Lead Receiver listening on http://127.0.0.1:" + port + "/crm/leads");
    }
}
