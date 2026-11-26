package com.uma.executor.api.springbootmultithreading.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class ThreadPoolConfig {

//    @Bean(name = "taskExecutor")
//    public Executor taskExecutor() {
//        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
//        executor.setCorePoolSize(3);
//        executor.setMaxPoolSize(5);
//        executor.setQueueCapacity(100);
//        executor.setThreadNamePrefix("userThread-");
//        executor.setWaitForTasksToCompleteOnShutdown(true);
//        executor.setAwaitTerminationSeconds(30);
//        executor.initialize();
//        return executor;
//    }

    // 1. Default pool for general user operations
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        return buildExecutor(3, 5, 100, "userThread-");
    }

    // 2. Heavy-duty pool for CSV parsing and batch inserts
    @Bean(name = "csvExecutor")
    public Executor csvExecutor() {
        return buildExecutor(2, 4, 50, "csvThread-");
    }

    // 3. Lightweight pool for notifications or external API calls
    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        return buildExecutor(5, 10, 200, "notifyThread-");
    }

    // Helper method to avoid repeating boilerplate setup
    private ThreadPoolTaskExecutor buildExecutor(int core, int max, int queue, String prefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(prefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}

