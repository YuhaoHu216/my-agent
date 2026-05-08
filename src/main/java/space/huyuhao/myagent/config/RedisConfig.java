package space.huyuhao.myagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import space.huyuhao.myagent.chatmemory.RedisChatMemory;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, byte[]> chatMemoryRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(RedisSerializer.string());
        template.setValueSerializer(RedisSerializer.byteArray());
        template.setHashKeySerializer(RedisSerializer.string());
        template.setHashValueSerializer(RedisSerializer.byteArray());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedisChatMemory redisChatMemory(RedisTemplate<String, byte[]> chatMemoryRedisTemplate) {
        return new RedisChatMemory(chatMemoryRedisTemplate);
    }
}