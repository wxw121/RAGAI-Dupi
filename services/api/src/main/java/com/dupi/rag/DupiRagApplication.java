package com.dupi.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class DupiRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(DupiRagApplication.class, args);
    }
}
