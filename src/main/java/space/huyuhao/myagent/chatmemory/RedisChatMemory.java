package space.huyuhao.myagent.chatmemory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.ArrayList;
import java.util.List;

public class RedisChatMemory implements ChatMemory {

    private static final String KEY_PREFIX = "chat:memory:";
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

    @Override
    public void add(String conversationId, List<Message> messages) {
        String key = KEY_PREFIX + conversationId;
        byte[] existing = redisTemplate.opsForValue().get(key);
        List<SlimMessage> all = existing == null ? new ArrayList<>() : deserialize(existing);
        for (Message msg : messages) {
            all.add(SlimMessage.from(msg));
        }
        redisTemplate.opsForValue().set(key, serialize(all));
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        byte[] data = redisTemplate.opsForValue().get(KEY_PREFIX + conversationId);
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
        redisTemplate.delete(KEY_PREFIX + conversationId);
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
}
