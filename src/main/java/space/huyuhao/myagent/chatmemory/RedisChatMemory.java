package space.huyuhao.myagent.chatmemory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.RedisTemplate;
import space.huyuhao.myagent.context.UserContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class RedisChatMemory implements ChatMemory {

    private static final String KEY_PREFIX = "chat:memory:";
    private static final String NAME_KEY_PREFIX = "chat:memory:name:";
    private static final int MAX_NAME_LENGTH = 50;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 只保留 role + text，去掉 metadata / finishReason 等冗余字段。
     * 只持久化 USER 和 ASSISTANT 消息，TOOL 等内部消息会被过滤。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SlimMessage(String role, String text, Long timestamp, List<Map<String, Object>> events) {
        Message toMessage() {
            return "USER".equals(role) ? new UserMessage(text) : new AssistantMessage(text);
        }

        /**
         * 只保留 USER 和 ASSISTANT 类型的消息，过滤掉 TOOL 等内部消息。
         * 返回 null 时调用方应跳过该消息。
         * 同时记录消息的持久化时间戳（毫秒）。
         * events 仅用于前端历史还原结构化事件（思考/工具调用/工具结果），纯文本路径传 null。
         */
        static SlimMessage from(Message msg) {
            MessageType type = msg.getMessageType();
            Long now = System.currentTimeMillis();
            if (type == MessageType.USER) {
                return new SlimMessage("USER", msg.getText(), now, null);
            } else if (type == MessageType.ASSISTANT) {
                return new SlimMessage("ASSISTANT", msg.getText(), now, null);
            }
            // TOOL 等内部消息不持久化，避免破坏对话格式
            return null;
        }
    }

    private final RedisTemplate<String, byte[]> redisTemplate;

    public RedisChatMemory(RedisTemplate<String, byte[]> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private Long resolveUserId(String conversationId) {
        Long userId = UserContext.getUserId();
        if (userId == null && conversationId != null) {
            userId = UserContext.getUserIdByConversationId(conversationId);
        }
        return userId;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        Long userId = resolveUserId(conversationId);
        String key = KEY_PREFIX + userId + ":" + conversationId;
        byte[] existing = redisTemplate.opsForValue().get(key);
        boolean isNew = existing == null;
        List<SlimMessage> all = isNew ? new ArrayList<>() : deserialize(existing);
        for (Message msg : messages) {
            SlimMessage slim = SlimMessage.from(msg);
            if (slim == null) {
                continue;
            }
            // 预写去重：避免与 addUserMessage 预写的同一 USER 重复落库
            if ("USER".equals(slim.role()) && !all.isEmpty()) {
                SlimMessage last = all.get(all.size() - 1);
                if ("USER".equals(last.role()) && slim.text() != null && slim.text().equals(last.text())) {
                    continue;
                }
            }
            all.add(slim);
        }
        redisTemplate.opsForValue().set(key, serialize(all));

        if (isNew) {
            String firstName = findFirstUserMessageText(messages);
            if (firstName != null) {
                setConversationName(conversationId, truncateName(firstName));
            }
        }
    }

    /**
     * 追加一条用户消息；若会话此前为空则同时设置会话名称（截取自用户首条消息）。
     * Agent 在开始执行时即调用，使会话在回复完成前就出现在列表。
     */
    public void addUserMessage(String conversationId, String userText) {
        Long userId = resolveUserId(conversationId);
        if (userId == null) {
            return;
        }
        String key = KEY_PREFIX + userId + ":" + conversationId;
        byte[] existing = redisTemplate.opsForValue().get(key);
        boolean isNew = existing == null;
        List<SlimMessage> all = isNew ? new ArrayList<>() : deserialize(existing);
        all.add(new SlimMessage("USER", userText, System.currentTimeMillis(), null));
        redisTemplate.opsForValue().set(key, serialize(all));

        if (isNew && userText != null && !userText.isBlank()) {
            setConversationName(conversationId, truncateName(userText));
        }
    }

    /**
     * 追加一条助手消息（携带 Agent 结构化事件），用于整轮执行结束后落库完整回答。
     */
    public void addAssistantMessage(String conversationId, String assistantText, List<Map<String, Object>> events) {
        Long userId = resolveUserId(conversationId);
        if (userId == null) {
            return;
        }
        String key = KEY_PREFIX + userId + ":" + conversationId;
        byte[] existing = redisTemplate.opsForValue().get(key);
        List<SlimMessage> all = existing == null ? new ArrayList<>() : deserialize(existing);
        all.add(new SlimMessage("ASSISTANT", assistantText, System.currentTimeMillis(), events));
        redisTemplate.opsForValue().set(key, serialize(all));
    }

    private String findFirstUserMessageText(List<Message> messages) {
        for (Message msg : messages) {
            if (msg.getMessageType() == MessageType.USER) {
                String text = msg.getText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private String truncateName(String text) {
        if (text.length() <= MAX_NAME_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_NAME_LENGTH) + "...";
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        Long userId = resolveUserId(conversationId);
        String key = KEY_PREFIX + userId + ":" + conversationId;
        byte[] data = redisTemplate.opsForValue().get(key);
        if (data == null) {
            return List.of();
        }
        List<SlimMessage> all = deserialize(data);
        int from = Math.max(0, all.size() - lastN);
        return all.subList(from, all.size()).stream()
                .map(SlimMessage::toMessage)
                .toList();
    }

    @Override
    public void clear(String conversationId) {
        Long userId = resolveUserId(conversationId);
        String key = KEY_PREFIX + userId + ":" + conversationId;
        redisTemplate.delete(key);
        UserContext.removeConversationUser(conversationId);
    }

    /**
     * 获取当前用户的所有会话ID
     */
    public List<String> getAllConversationIds() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return List.of();
        }

        String pattern = KEY_PREFIX + userId + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }

        return keys.stream()
                .map(key -> {
                    int prefixEndIndex = key.indexOf(':', KEY_PREFIX.length() + String.valueOf(userId).length() + 1);
                    return key.substring(prefixEndIndex + 1);
                })
                .collect(Collectors.toList());
    }

    /**
     * 获取当前用户的特定会话消息
     */
    public List<SlimMessage> getConversationMessages(String conversationId) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return List.of();
        }

        String key = KEY_PREFIX + userId + ":" + conversationId;
        byte[] data = redisTemplate.opsForValue().get(key);
        
        if (data == null) {
            return List.of();
        }

        return deserialize(data);
    }

    /**
     * 删除当前用户的特定会话
     */
    public boolean deleteConversation(String conversationId) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return false;
        }

        String key = KEY_PREFIX + userId + ":" + conversationId;
        String nameKey = NAME_KEY_PREFIX + userId + ":" + conversationId;
        Boolean result = redisTemplate.delete(key);
        redisTemplate.delete(nameKey);
        UserContext.removeConversationUser(conversationId);
        return result != null && result;
    }

    /**
     * 获取会话名称
     */
    public String getConversationName(String conversationId) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return null;
        }
        String nameKey = NAME_KEY_PREFIX + userId + ":" + conversationId;
        byte[] data = redisTemplate.opsForValue().get(nameKey);
        return data != null ? new String(data, java.nio.charset.StandardCharsets.UTF_8) : null;
    }

    /**
     * 设置会话名称（内部使用）
     */
    private void setConversationName(String conversationId, String name) {
        Long userId = resolveUserId(conversationId);
        if (userId == null) {
            return;
        }
        String nameKey = NAME_KEY_PREFIX + userId + ":" + conversationId;
        redisTemplate.opsForValue().set(nameKey, name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 更新会话名称（用户调用）
     */
    public boolean updateConversationName(String conversationId, String name) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return false;
        }
        String messagesKey = KEY_PREFIX + userId + ":" + conversationId;
        if (Boolean.FALSE.equals(redisTemplate.hasKey(messagesKey))) {
            return false;
        }
        String trimmed = name != null ? name.trim() : "";
        if (trimmed.isEmpty()) {
            return false;
        }
        setConversationName(conversationId, truncateName(trimmed));
        return true;
    }

    /**
     * 获取当前用户的所有会话摘要信息
     */
    public List<ConversationSummary> getAllConversationsSummary() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return List.of();
        }

        String pattern = KEY_PREFIX + userId + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }

        List<ConversationSummary> summaries = new ArrayList<>();
        
        for (String key : keys) {
            try {
                String conversationId = extractConversationIdFromKey(key, userId);
                byte[] data = redisTemplate.opsForValue().get(key);
                
                if (data != null) {
                    List<SlimMessage> messages = deserialize(data);
                    
                    String lastMessagePreview = "";
                    String lastMessageType = "";
                    if (!messages.isEmpty()) {
                        SlimMessage lastMessage = messages.get(messages.size() - 1);
                        lastMessagePreview = lastMessage.text().length() > 50 ? 
                                lastMessage.text().substring(0, 50) + "..." : lastMessage.text();
                        lastMessageType = lastMessage.role();
                    }
                    
                    // 优先使用最后一条消息的真实时间戳，旧数据回退到 TTL 推算
                    Long lastActivityTime = null;
                    if (!messages.isEmpty()) {
                        lastActivityTime = messages.get(messages.size() - 1).timestamp();
                    }
                    if (lastActivityTime == null) {
                        lastActivityTime = getExpirationTime(key);
                    }

                    String conversationName = getConversationName(conversationId);
                    if (conversationName == null || conversationName.isEmpty()) {
                        conversationName = "未命名会话";
                    }

                    summaries.add(new ConversationSummary(
                            conversationId,
                            conversationName,
                            messages.size(),
                            lastMessagePreview,
                            lastMessageType,
                            lastActivityTime
                    ));
                }
            } catch (Exception e) {
                // 如果某个会话解析失败，跳过它并继续处理其他会话
                continue;
            }
        }

        return summaries;
    }

    /**
     * 从Redis键中提取会话ID
     */
    private String extractConversationIdFromKey(String key, Long userId) {
        String prefixWithUserId = KEY_PREFIX + userId + ":";
        return key.substring(prefixWithUserId.length());
    }

    private Long getExpirationTime(String key) {
        try {
            Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            if (ttl != null) {
                return System.currentTimeMillis() + (ttl * 1000); // 转换为毫秒时间戳
            }
        } catch (Exception e) {
            // 忽略错误，返回null
        }
        return null;
    }

    private byte[] serialize(List<SlimMessage> messages) {
        try {
            return objectMapper.writeValueAsBytes(messages);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize messages", e);
        }
    }

    private List<SlimMessage> deserialize(byte[] data) {
        try {
            return objectMapper.readValue(data,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, SlimMessage.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize messages", e);
        }
    }

    /**
     * 会话摘要信息
     */
    public record ConversationSummary(
            String conversationId,
            String name,
            int messageCount,
            String lastMessagePreview,
            String lastMessageType,
            Long lastActivityTime
    ) {}
}