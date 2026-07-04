package com.dupi.rag.service;

import com.dupi.rag.config.MinioProperties;
import com.dupi.rag.exception.ResourceNotFoundException;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MinioStorageServiceTest {

    @Test
    void ensureBucketCreatesMissingBucketAndUploadReturnsObjectKey() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        when(minioClient.bucketExists(any())).thenReturn(false);
        MinioStorageService service = new MinioStorageService(minioClient, props());

        assertThat(service.upload("obj", new ByteArrayInputStream("abc".getBytes()), 3L, "text/plain")).isEqualTo("obj");

        verify(minioClient).makeBucket(any());
        verify(minioClient).putObject(any());
    }

    @Test
    void ensureBucketSkipsCreationWhenBucketExistsAndWrapsFailures() throws Exception {
        MinioClient exists = mock(MinioClient.class);
        when(exists.bucketExists(any())).thenReturn(true);
        new MinioStorageService(exists, props()).ensureBucket();
        verify(exists, never()).makeBucket(any());

        MinioClient broken = mock(MinioClient.class);
        when(broken.bucketExists(any())).thenThrow(new RuntimeException("down"));
        assertThatThrownBy(() -> new MinioStorageService(broken, props()).ensureBucket())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to ensure MinIO bucket");
    }

    @Test
    void downloadReturnsStreamOrRaisesNotFoundAndDeleteSwallowsFailures() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        GetObjectResponse response = mock(GetObjectResponse.class);
        when(minioClient.getObject(any())).thenReturn(response);
        MinioStorageService service = new MinioStorageService(minioClient, props());

        assertThat(service.download("obj")).isSameAs(response);

        when(minioClient.getObject(any())).thenThrow(new RuntimeException("missing"));
        assertThatThrownBy(() -> service.download("missing"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Object not found");

        doThrow(new RuntimeException("delete failed")).when(minioClient).removeObject(any());
        service.delete("obj");
        verify(minioClient).removeObject(any());
    }

    private static MinioProperties props() {
        MinioProperties props = new MinioProperties();
        props.setEndpoint("http://localhost:9000");
        props.setAccessKey("ak");
        props.setSecretKey("sk");
        props.setBucket("bucket");
        return props;
    }
}
