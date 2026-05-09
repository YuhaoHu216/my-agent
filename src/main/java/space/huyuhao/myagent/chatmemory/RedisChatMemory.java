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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class RedisChatMemory implements ChatMemory {

    private static final String KEY_PREFIX = "chat:memory:";
    private static final String NAME_KEY_PREFIX = "chat:memory:name:";
    private static final int MAX_NAME_LENGTH = 50;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 只保留 role + text，去掉 metadata / finishReason 等冗余字段
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SlimMessage(String role, String text) {
        Message toMessage() {
            return "USER".equals(role) ? new UserMessage(text) : new AssistantMessage(text);
        }

        static SlimMessage from(Message msg) {
            return new SlimMessage(
                    msg.getMessageType() == MessageType.USER ? "USER" : "ASSISTANT",
                    msg.getText()
            );
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
            all.add(SlimMessage.from(msg));
        }
        redisTemplate.opsForValue().set(key, serialize(all));

        if (isNew) {
            String firstName = findFirstUserMessageText(messages);
            if (firstName != null) {
                setConversationName(conversationId, truncateName(firstName));
            }
        }
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
                    
                    // 获取过期时间作为最后活动时间
                    Long expirationTime = getExpirationTime(key);
                    
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
                            expirationTime
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