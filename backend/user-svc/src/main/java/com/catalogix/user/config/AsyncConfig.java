package com.catalogix.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// Backs every @Async method (currently UserEventPublisher's RabbitMQ relay
// methods) with a small bounded pool instead of Spring's default
// SimpleAsyncTaskExecutor, which spins up an unbounded new thread per call.
@Configuration
public class AsyncConfig {

    @Bean
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("user-async-");
        executor.initialize();
        return executor;
    }
}
