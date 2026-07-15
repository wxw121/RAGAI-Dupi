package com.dupi.rag.service;

import java.util.Map;

public record MilvusRecoverySchema(String metric, int dimension, Map<String, Object> settings) {
}
