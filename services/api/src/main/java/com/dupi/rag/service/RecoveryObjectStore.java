package com.dupi.rag.service;

import java.io.InputStream;
import java.util.List;

public interface RecoveryObjectStore {
    void put(String bucket, String key, InputStream input) throws Exception;
    default RecoveryObjectWriteResult putIfAbsent(String bucket, String key, InputStream input) throws Exception {
        throw new UnsupportedOperationException("Atomic create-if-absent is not implemented");
    }
    String version(String bucket, String key) throws Exception;
    InputStream get(String bucket, String key) throws Exception;
    List<String> list(String bucket, String prefix) throws Exception;
    void delete(String bucket, String key) throws Exception;
}
