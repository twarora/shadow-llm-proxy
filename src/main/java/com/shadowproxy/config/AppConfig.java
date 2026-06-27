package com.shadowproxy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class AppConfig {

    /**
     * Dedicated thread pool for shadow work.
     *
     * <p>This is the heart of the decoupling guarantee: shadow tasks run here, on threads that are
     * completely separate from the servlet request thread that served the primary response. Once
     * {@code /generate} returns, the request thread is recycled and the client connection may close,
     * but the shadow task keeps running on this pool until it finishes.
     *
     * <p>The queue is bounded so a flood of slow shadow calls can never exhaust memory; if the queue
     * is full the task is rejected and recorded as a shadow failure (never propagated to the user).
     */
    @Bean(name = "shadowExecutor", destroyMethod = "shutdown")
    public ExecutorService shadowExecutor(ProxyProperties props) {
        ProxyProperties.ShadowExecutor cfg = props.getShadowExecutor();
        AtomicInteger counter = new AtomicInteger();
        return new ThreadPoolExecutor(
                cfg.getPoolSize(),
                cfg.getPoolSize(),
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(cfg.getQueueCapacity()),
                runnable -> {
                    Thread t = new Thread(runnable, "shadow-worker-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
