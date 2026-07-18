package com.dupi.rag.exception;

public class UploadPayloadTooLargeException extends RuntimeException {
    public UploadPayloadTooLargeException(String message) {
        super(message);
    }
}
