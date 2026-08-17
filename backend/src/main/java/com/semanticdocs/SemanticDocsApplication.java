package com.semanticdocs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point. @SpringBootApplication turns on component scanning for everything under
 * com.semanticdocs, plus auto-configuration (Spring sees postgresql on the classpath and
 * wires a DataSource, sees spring-web and starts Tomcat, and so on).
 */
@SpringBootApplication
@EnableAsync    // lets @Async methods run on a background thread pool
@EnableCaching  // lets @Cacheable talk to Redis
@EnableScheduling // runs the periodic index flush
public class SemanticDocsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SemanticDocsApplication.class, args);
    }
}
