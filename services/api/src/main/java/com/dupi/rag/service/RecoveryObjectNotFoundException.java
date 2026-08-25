package com.dupi.rag.service;

/** A positive object-store answer that a key does not exist. */
public class RecoveryObjectNotFoundException extends Exception {
    public RecoveryObjectNotFoundException(String bucket, String key) {
        super("Recovery object not found: " + bucket + "/" + key);
    }

    public RecoveryObjectNotFoundException(String bucket, String key, Throwable cause) {
        super("Recovery object not found: " + bucket + "/" + key, cause);
    }
}
