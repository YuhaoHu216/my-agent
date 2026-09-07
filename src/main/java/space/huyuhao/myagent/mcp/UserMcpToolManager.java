package space.huyuhao.myagent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.ClientMcpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import space.huyuhao.myagent.dto.McpToolInfoDto;
import space.huyuhao.myagent.entity.UserMcpServer;
import space.huyuhao.myagent.mapper.UserMcpServerMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 用户自定义 MCP 工具管理器：按用户从库中加载启用的 SSE 服务，
 * 复用长连接客户端，并通过 McpToolUtils 转为 ToolCallback[] 供 Agent/Chat 模式使用。
 */
@Slf4j
@Component
public class UserMcpToolManager {

    private static final ToolCallback[] EMPTY_TOOLS = new ToolCallback[0];

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final UserMcpServerMapper userMcpServerMapper;
    private final long requestTimeoutMs;

    /** userId -> 合并后的工具数组 */
    private final ConcurrentHashMap<Long, ToolCallback[]> toolsCache = new ConcurrentHashMap<>();
    /** key = "userId:serverId" -> SSE 长连接客户端 */
    private final ConcurrentHashMap<String, McpSyncClient> clientCache = new ConcurrentHashMap<>();
    /** 每用户一把锁，防止并发重复建连 */
    private final ConcurrentHashMap<Long, Object> userLocks = new ConcurrentHashMap<>();

    /** 协议探测用短超时 HTTP 客户端 */
    private static final HttpClient probeHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public UserMcpToolManager(UserMcpServerMapper userMcpServerMapper,
                              @Value("${my-agent.mcp.request-timeout-ms:30000}") long requestTimeoutMs) {
        this.userMcpServerMapper = userMcpServerMapper;
        this.requestTimeoutMs = requestTimeoutMs;
    }

    /**
     * 获取某用户启用的全部 MCP 工具。缓存命中直接返回，未命中持锁加载并构建。
     */
    public ToolCallback[] getToolsForUser(Long userId) {
        if (userId == null) {
            return EMPTY_TOOLS;
        }
        ToolCallback[] cached = toolsCache.get(userId);
        if (cached != null) {
            return cached;
        }
        Object lock = userLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            cached = toolsCache.get(userId);
            if (cached != null) {
                return cached;
            }
            ToolCallback[] tools = loadAndBuild(userId);
            toolsCache.put(userId, tools);
            return tools;
        }
    }

    /**
     * 只返回绑定到指定 MCP server 的工具（仍按该用户校验归属 + 启用状态，防越权）。
     * 用于自定义 agent：仅注入其绑定的 server 工具，不复用全量 getToolsForUser。
     * 单个服务失败仅跳过，不拖垮整体。
     */
    public ToolCallback[] getToolsForUserByServerIds(Long userId, List<Long> serverIds) {
        if (userId == null || serverIds == null || serverIds.isEmpty()) {
            return EMPTY_TOOLS;
        }
        Set<Long> serverIdSet = new HashSet<>(serverIds);
        List<UserMcpServer> bound = userMcpServerMapper.selectEnabledByUserId(userId).stream()
                .filter(server -> serverIdSet.contains(server.getId()))
                .collect(Collectors.toList());
        if (bound.isEmpty()) {
            return EMPTY_TOOLS;
        }
        List<McpSyncClient> clients = new ArrayList<>();
        for (UserMcpServer server : bound) {
            McpSyncClient client = getOrCreateClient(userId, server);
            if (client != null) {
                clients.add(client);
            }
        }
        if (clients.isEmpty()) {
            return EMPTY_TOOLS;
        }
        return McpToolUtils.getToolCallbacksFromSyncClients(clients.toArray(new McpSyncClient[0]))
                .toArray(ToolCallback[]::new);
    }

    /**
     * 加载用户启用的服务并逐个建连，收集成功客户端后转为工具数组。
     * 单个服务失败仅跳过，不拖垮整体。
     */
    private ToolCallback[] loadAndBuild(Long userId) {
        List<UserMcpServer> servers = userMcpServerMapper.selectEnabledByUserId(userId);
        if (servers.isEmpty()) {
            return EMPTY_TOOLS;
        }
        List<McpSyncClient> clients = new ArrayList<>();
        for (UserMcpServer server : servers) {
            McpSyncClient client = getOrCreateClient(userId, server);
            if (client != null) {
                clients.add(client);
            }
        }
        if (clients.isEmpty()) {
            return EMPTY_TOOLS;
        }
        return McpToolUtils.getToolCallbacksFromSyncClients(clients.toArray(new McpSyncClient[0]))
                .toArray(ToolCallback[]::new);
    }

    /**
     * 构建 MCP 同步客户端：自动探测服务协议（Streamable HTTP 或传统 SSE）后建连并完成 initialize 握手。
     */
    private McpSyncClient buildClient(String url) {
        ClientMcpTransport transport = createTransport(url);
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(requestTimeoutMs))
                .build();
        client.initialize();
        return client;
    }

    /**
     * 自动探测服务协议：POST ping 得到合法 JSON-RPC 响应说明支持 Streamable HTTP，否则回退传统 SSE。
     */
    private ClientMcpTransport createTransport(String url) {
        return isStreamableHttp(url)
                ? new HttpClientStreamableClientTransport(url)
                : new HttpClientSseClientTransport(url);
    }

    private boolean isStreamableHttp(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}"))
                    .build();
            HttpResponse<String> response = probeHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return false;
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            return contentType.contains("json") || contentType.contains("event-stream")
                    || response.body().contains("\"jsonrpc\"");
        } catch (Exception e) {
            log.debug("Streamable HTTP 协议探测失败，回退传统 SSE: url={}", url, e);
            return false;
        }
    }

    /**
     * 从缓存取或新建用户某个 MCP 服务的同步长连接客户端。
     * 建连/初始化失败仅记日志并更新连接状态，返回 null。
     */
    private McpSyncClient getOrCreateClient(Long userId, UserMcpServer server) {
        String key = userId + ":" + server.getId();
        McpSyncClient cached = clientCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            McpSyncClient client = buildClient(server.getUrl());
            McpSyncClient existing = clientCache.putIfAbsent(key, client);
            if (existing != null) {
                client.close();
                return existing;
            }
            updateConnectStatus(server.getId(), 1);
            return client;
        } catch (Exception e) {
            log.warn("创建 MCP 客户端失败: userId={}, serverName={}, url={}",
                    userId, server.getServerName(), server.getUrl(), e);
            updateConnectStatus(server.getId(), 2);
            return null;
        }
    }

    /**
     * 使某用户的工具缓存与客户端连接失效（服务增删改/启停后调用），下次请求重新加载。
     */
    public void invalidate(Long userId) {
        if (userId == null) {
            return;
        }
        Object lock = userLocks.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            toolsCache.remove(userId);
            String prefix = userId + ":";
            List<String> keys = new ArrayList<>();
            clientCache.forEach((key, client) -> {
                if (key.startsWith(prefix)) {
                    keys.add(key);
                }
            });
            for (String key : keys) {
                McpSyncClient client = clientCache.remove(key);
                if (client != null) {
                    closeClient(key, client);
                }
            }
        }
    }

    /**
     * 临时建连测试指定 URL，返回连接结果与工具数，不进入缓存。
     */
    public McpTestResult testConnection(String url) {
        McpSyncClient client = null;
        try {
            client = buildClient(url);
            int count = client.listTools().tools().size();
            return new McpTestResult(true, "连接成功，发现 " + count + " 个工具", count);
        } catch (Exception e) {
            log.warn("MCP 连接测试失败: url={}", url, e);
            return new McpTestResult(false, "连接失败: " + e.getMessage(), 0);
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 获取某服务可用的工具列表（工具名/描述/参数 schema），供前端调试页展示。
     */
    public List<McpToolInfoDto> listTools(Long userId, UserMcpServer server) {
        McpSyncClient client = getOrCreateClient(userId, server);
        if (client == null) {
            throw new IllegalStateException("MCP 服务连接失败");
        }
        return client.listTools().tools().stream()
                .map(this::toToolInfo)
                .collect(Collectors.toList());
    }

    /**
     * 调用某服务的指定工具，返回执行结果文本（调试用）。
     */
    public McpCallResult callTool(Long userId, UserMcpServer server, String toolName, Map<String, Object> arguments) {
        McpSyncClient client = getOrCreateClient(userId, server);
        if (client == null) {
            throw new IllegalStateException("MCP 服务连接失败");
        }
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, arguments));
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent text) {
                sb.append(text.text());
            } else {
                sb.append(content);
            }
            sb.append('\n');
        }
        return new McpCallResult(!result.isError(), sb.toString().trim());
    }

    /**
     * Tool 序列化为 Map 提取字段（JsonSchema 为包私有类，不能直接访问其方法）。
     */
    @SuppressWarnings("unchecked")
    private McpToolInfoDto toToolInfo(McpSchema.Tool tool) {
        Map<String, Object> toolMap = OBJECT_MAPPER.convertValue(tool, new TypeReference<Map<String, Object>>() {
        });
        McpToolInfoDto dto = new McpToolInfoDto();
        dto.setName((String) toolMap.get("name"));
        dto.setDescription((String) toolMap.get("description"));
        dto.setInputSchema((Map<String, Object>) toolMap.get("inputSchema"));
        return dto;
    }

    private void updateConnectStatus(Long serverId, int status) {
        UserMcpServer update = new UserMcpServer();
        update.setId(serverId);
        update.setConnectStatus(status);
        userMcpServerMapper.updateById(update);
    }

    private void closeClient(String key, McpSyncClient client) {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("关闭 MCP 客户端失败: {}", key, e);
        }
    }

    /** 应用关闭时释放全部连接 */
    @PreDestroy
    public void shutdown() {
        log.info("关闭全部 MCP 客户端连接");
        clientCache.forEach(this::closeClient);
        clientCache.clear();
    }

    /** MCP 工具调用结果 */
    public record McpCallResult(boolean success, String content) {
    }

    /** MCP 连接测试结果 */
    public record McpTestResult(boolean success, String message, int toolCount) {
    }
}
