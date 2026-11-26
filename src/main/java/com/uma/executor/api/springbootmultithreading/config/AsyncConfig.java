package com.uma.executor.api.springbootmultithreading.config;


import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;

@Slf4j
@Configuration
@EnableAsync //inform Spring framework to run task in bg using threadpool concept
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);      // Runs 2 tasks concurrently
        executor.setMaxPoolSize(2);       // Scales up to 2 thread. increase to 5 to scale if queue fills
        executor.setQueueCapacity(100);   // Holds up to 100 waiting tasks
        executor.setThreadNamePrefix("userThread-");
        executor.initialize();
        return executor;
    }


}
