package com.dupi.rag.exception;

import com.dupi.rag.dto.ApiErrorResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(
                "not_found", ex.getMessage(), "request", "Confirm the resource still exists and is visible to your account."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(error(
                "validation_error",
                ex.getBindingResult().getAllErrors().get(0).getDefaultMessage(),
                "request",
                "Check the form values and submit again."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(error(
                "bad_request",
                ex.getMessage() != null ? ex.getMessage() : "Bad request",
                "request",
                "Check the request parameters and try again."));
    }

    @ExceptionHandler(RetrievalProfileConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleRetrievalProfileConflict(RetrievalProfileConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error(
                "retrieval_profile_gate_blocked",
                ex.getMessage() != null ? ex.getMessage() : "Retrieval profile gate blocked",
                "retrieval_profile",
                "Run a current RAG evaluation and only activate profiles with a passed gate."));
    }

    @ExceptionHandler(ChatPipelineException.class)
    public ResponseEntity<ApiErrorResponse> handleChatPipeline(ChatPipelineException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(
                "chat_pipeline_error",
                ex.getMessage(),
                ex.getStage(),
                ex.getSuggestion()));
    }

    @ExceptionHandler(KnowledgeBaseMaintenanceException.class)
    public ResponseEntity<ApiErrorResponse> handleMaintenance(KnowledgeBaseMaintenanceException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error(
                "knowledge_base_maintenance",
                ex.getMessage(),
                "recovery",
                "Wait for the active recovery archive to finish, then retry the mutation."));
    }

    @ExceptionHandler(UploadIdempotencyConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleUploadIdempotencyConflict(UploadIdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error(
                "upload_idempotency_conflict",
                ex.getMessage(),
                "upload",
                "Use a new Idempotency-Key when uploading a different file."));
    }

    @ExceptionHandler(UploadPayloadTooLargeException.class)
    public ResponseEntity<ApiErrorResponse> handleUploadPayloadTooLarge(UploadPayloadTooLargeException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(error(
                "upload_payload_too_large",
                ex.getMessage(),
                "upload",
                "Choose a smaller file or increase the configured upload quota."));
    }

    @ExceptionHandler(UploadQuotaExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleUploadQuotaExceeded(UploadQuotaExceededException ex) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
        if (ex.getRetryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        }
        return builder.body(error(
                "upload_quota_exceeded",
                ex.getMessage(),
                "upload",
                "Wait for quota to free up or delete retained documents before retrying."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(
                "internal_error",
                ex.getMessage() != null ? ex.getMessage() : "Unexpected error",
                "unknown",
                "Use the requestId to inspect server logs."));
    }

    private ApiErrorResponse error(String error, String message, String stage, String suggestion) {
        return ApiErrorResponse.of(error, message, stage, suggestion, requestId());
    }

    private String requestId() {
        String traceId = MDC.get("traceId");
        return traceId == null || traceId.isBlank() ? "unavailable" : traceId;
    }
}
