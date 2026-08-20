package space.huyuhao.myagent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.ClientMcpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Streamable HTTP 客户端传输：直接 POST JSON-RPC 到端点并解析响应（JSON 或 SSE）。
 * 兼容仅提供 Streamable HTTP 端点的 MCP 服务（如高德 amap，对传统 SSE 的 GET 握手返回 405）。
 * 基于 mcp SDK 0.7.0 的 ClientMcpTransport 接口实现。
 */
public class HttpClientStreamableClientTransport implements ClientMcpTransport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String url;
    private final AtomicReference<String> sessionId = new AtomicReference<>();
    private Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;

    public HttpClientStreamableClientTransport(String url) {
        this.url = url;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        // Streamable HTTP 无预连接握手，仅保存 session 的消息处理器
        this.handler = handler;
        return Mono.empty();
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        try {
            String body = OBJECT_MAPPER.writeValueAsString(message);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("User-Agent", "my-agent-mcp/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            String sid = sessionId.get();
            if (sid != null) {
                builder.header("Mcp-Session-Id", sid);
            }
            return Mono.fromFuture(httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()))
                    .flatMap(response -> {
                        if (response.statusCode() >= 400) {
                            return Mono.error(new McpError("HTTP " + response.statusCode() + ": " + response.body()));
                        }
                        // 捕获服务器下发的会话 ID（若有），供后续请求携带
                        response.headers().firstValue("Mcp-Session-Id").ifPresent(sessionId::set);
                        try {
                            List<McpSchema.JSONRPCMessage> responses = parseResponse(response.body(),
                                    response.headers().firstValue("Content-Type").orElse(""));
                            // 响应经 handler 回传 session，匹配 pendingResponses 完成对应请求
                            responses.forEach(msg -> handler.apply(Mono.just(msg)).subscribe());
                            return Mono.empty();
                        } catch (IOException e) {
                            return Mono.error(e);
                        }
                    });
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    private List<McpSchema.JSONRPCMessage> parseResponse(String body, String contentType) throws IOException {
        List<McpSchema.JSONRPCMessage> messages = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return messages;
        }
        boolean isSse = contentType.contains("event-stream") || body.trim().startsWith("data:");
        if (isSse) {
            // SSE 响应：按空行分隔事件块，取 data 字段作为 JSON-RPC 消息
            for (String block : body.split("\\r?\\n\\r?\\n")) {
                for (String line : block.split("\\r?\\n")) {
                    if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        if (!data.isEmpty() && !"[DONE]".equals(data)) {
                            messages.add(McpSchema.deserializeJsonRpcMessage(OBJECT_MAPPER, data));
                        }
                        break;
                    }
                }
            }
        } else {
            messages.add(McpSchema.deserializeJsonRpcMessage(OBJECT_MAPPER, body));
        }
        return messages;
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.empty();
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
        return OBJECT_MAPPER.convertValue(data, typeRef);
    }
}
