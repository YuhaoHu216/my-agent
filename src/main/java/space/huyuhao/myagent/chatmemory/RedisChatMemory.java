package space.huyuhao.myagent.chatmemory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.RedisTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public class RedisChatMemory implements ChatMemory {

    private static final String KEY_PREFIX = "chat:memory:";
    private static final Kryo kryo = new Kryo();

    static {
        kryo.setRegistrationRequired(false);
        kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
    }

    private final RedisTemplate<String, byte[]> redisTemplate;

    public RedisChatMemory(RedisTemplate<String, byte[]> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        String key = KEY_PREFIX + conversationId;
        byte[] existing = redisTemplate.opsForValue().get(key);
        List<Message> all = existing == null ? new ArrayList<>() : deserialize(existing);
        all.addAll(messages);
        redisTemplate.opsForValue().set(key, serialize(all));
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        byte[] data = redisTemplate.opsForValue().get(KEY_PREFIX + conversationId);
        if (data == null) {
            return List.of();
        }
        List<Message> all = deserialize(data);
        int from = Math.max(0, all.size() - lastN);
        return all.subList(from, all.size());
    }

    @Override
    public void clear(String conversationId) {
        redisTemplate.delete(KEY_PREFIX + conversationId);
    }

    private byte[] serialize(List<Message> messages) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (Output output = new Output(baos)) {
            kryo.writeObject(output, messages);
        }
        return baos.toByteArray();
    }

    private List<Message> deserialize(byte[] data) {
        try (Input input = new Input(new ByteArrayInputStream(data))) {
            return kryo.readObject(input, ArrayList.class);
        }
    }
}
