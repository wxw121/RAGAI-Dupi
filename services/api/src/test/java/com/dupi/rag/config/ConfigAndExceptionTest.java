package com.dupi.rag.config;

import com.dupi.rag.exception.GlobalExceptionHandler;
import com.dupi.rag.exception.ResourceNotFoundException;
import io.milvus.client.MilvusServiceClient;
import io.minio.MinioClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.CorsFilter;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConfigAndExceptionTest {

    @Test
    void corsConfigAllowsLocalDevelopmentOriginsAndStandardMethods() {
        CorsFilter filter = new CorsConfig().corsFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api");
        request.addHeader("Origin", "http://localhost:5173");
        request.addHeader("Access-Control-Request-Method", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, mock(FilterChain.class));
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
    }

    @Test
    void traceIdFilterPropagatesExistingTraceIdOrCreatesOne() throws ServletException, IOException {
        TraceIdFilter filter = new TraceIdFilter();
        MockHttpServletRequest withTrace = new MockHttpServletRequest();
        withTrace.addHeader("X-Trace-Id", "trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(withTrace, response, chain);

        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("trace-123");
        verify(chain).doFilter(withTrace, response);

        MockHttpServletResponse generated = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), generated, mock(FilterChain.class));
        assertThat(generated.getHeader("X-Trace-Id")).hasSize(16);
    }

    @Test
    void propertyBeansAndWebClientConfigExposeMutableSettings() {
        LlmProperties llm = new LlmProperties();
        llm.getChat().setBaseUrl("chat-url");
        llm.getChat().setApiKey("chat-key");
        llm.getChat().setModel("chat-model");
        llm.getEmbedding().setBaseUrl("embed-url");
        llm.getEmbedding().setApiKey("embed-key");
        llm.getEmbedding().setModel("embed-model");
        llm.getEmbedding().setDimension(12);
        MinioProperties minio = new MinioProperties();
        minio.setEndpoint("http://minio");
        minio.setAccessKey("ak");
        minio.setSecretKey("sk");
        minio.setBucket("bucket");
        MilvusProperties milvus = new MilvusProperties();
        milvus.setHost("localhost");
        milvus.setPort(19530);
        milvus.setCollection("collection");
        RagProperties rag = new RagProperties();
        rag.setDefaultTopK(5);
        rag.setMaxContextChars(1000);

        assertThat(llm.getChat().getBaseUrl()).isEqualTo("chat-url");
        assertThat(llm.getEmbedding().getDimension()).isEqualTo(12);
        assertThat(minio.getBucket()).isEqualTo("bucket");
        assertThat(milvus.getCollection()).isEqualTo("collection");
        assertThat(rag.getMaxContextChars()).isEqualTo(1000);
        assertThat(new WebClientConfig().webClientBuilder().build()).isNotNull();
    }

    @Test
    void minioConfigBuildsClientAndMilvusConfigClosesSafelyWhenUnused() {
        MinioProperties minio = new MinioProperties();
        minio.setEndpoint("http://localhost:9000");
        minio.setAccessKey("ak");
        minio.setSecretKey("sk");
        MinioClient minioClient = new MinioConfig().minioClient(minio);
        assertThat(minioClient).isNotNull();

        MilvusConfig milvusConfig = new MilvusConfig();
        milvusConfig.close();
        MilvusServiceClient milvusClient = mock(MilvusServiceClient.class);
        ReflectionTestUtils.setField(milvusConfig, "client", milvusClient);
        milvusConfig.close();
        verify(milvusClient).close();
    }

    @Test
    void globalExceptionHandlerMapsNotFoundValidationAndGenericErrors() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var notFound = handler.handleNotFound(new ResourceNotFoundException("missing"));
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getBody()).containsEntry("error", "not_found").containsEntry("message", "missing");

        Object target = new Object();
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(target, "target");
        binding.addError(new FieldError("target", "name", "must not be blank"));
        MethodArgumentNotValidException validationEx = new MethodArgumentNotValidException(null, binding);
        var validation = handler.handleValidation(validationEx);
        assertThat(validation.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(validation.getBody()).containsEntry("error", "validation_error").containsEntry("message", "must not be blank");

        var generic = handler.handleGeneric(new RuntimeException());
        assertThat(generic.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(generic.getBody()).containsEntry("message", "Unexpected error");
    }
}
