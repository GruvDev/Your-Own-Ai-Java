package com.semanticdocs.config;

import java.time.Duration;
import java.util.concurrent.Executor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

/**
 * Beans that are not tied to one feature: the thread pool used for ingestion and the
 * HTTP client used to talk to Ollama.
 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {

    /**
     * The pool that runs @Async ingestion jobs.
     *
     * <p>Sizing rationale: embedding is IO-bound (we wait on Ollama), so a few threads are
     * plenty and more would just queue up at the model anyway. The bounded queue matters -
     * an unbounded queue turns a traffic spike into an OutOfMemoryError instead of a rejection.
     * CallerRunsPolicy means that when we are saturated, the uploading thread does the work
     * itself, which naturally slows down intake instead of dropping it.
     */
    @Bean("ingestionExecutor")
    public Executor ingestionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ingest-");
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * A separate pool for chat streaming.
     *
     * <p>These two workloads must not share a pool. Ingestion tasks are long (a big PDF can
     * occupy a thread for minutes) and there can be a burst of them; chat streaming is
     * interactive and a user is watching. Share one pool and a batch of uploads fills the
     * queue, so every chat request sits behind them and appears to hang. Isolating pools by
     * workload - sometimes called the bulkhead pattern - is what stops slow background work
     * from starving the interactive path.
     *
     * <p>The queue is deliberately tiny. A queued chat request is a user staring at a blank
     * screen, so it is better to reject quickly than to accept and be slow.
     */
    @Bean("streamingExecutor")
    public Executor streamingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("stream-");
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    public RestClient ollamaRestClient(AppProperties properties) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(properties.getOllama().getTimeoutSeconds()));
        return RestClient.builder()
                .baseUrl(properties.getOllama().getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
