package com.dupi.rag.config;

import java.time.Duration;

public interface LoginFailureStore {

    LoginFailureState get(String username);

    void save(String username, LoginFailureState state, Duration ttl);

    void clear(String username);

    record LoginFailureState(int failures, long lastFailureEpochSecond) {
    }
}
