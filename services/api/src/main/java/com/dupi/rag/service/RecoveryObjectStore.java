package com.dupi.rag.service;

import java.io.InputStream;
import java.util.List;

public interface RecoveryObjectStore {
    void put(String bucket, String key, InputStream input) throws Exception;
    InputStream get(String bucket, String key) throws Exception;
    List<String> list(String bucket, String prefix) throws Exception;
    void delete(String bucket, String key) throws Exception;
}
