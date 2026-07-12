package com.dupi.rag.config;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisLoginFailureStore implements LoginFailureStore {

    private static final String KEY_PREFIX = "dupi:auth:login-failures:";
    private final StringRedisTemplate redisTemplate;

    @Override
    public LoginFailureState get(String username) {
        String payload = redisTemplate.opsForValue().get(key(username));
        if (payload == null || payload.isBlank()) {
            return null;
        }
        String[] parts = payload.split(":", -1);
        if (parts.length != 2) {
            return null;
        }
        try {
            return new LoginFailureState(Integer.parseInt(parts[0]), Long.parseLong(parts[1]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Override
    public void save(String username, LoginFailureState state, Duration ttl) {
        redisTemplate.opsForValue().set(key(username), state.failures() + ":" + state.lastFailureEpochSecond(), ttl);
    }

    @Override
    public void clear(String username) {
        redisTemplate.delete(key(username));
    }

    private String key(String username) {
        return KEY_PREFIX + username;
    }
}
