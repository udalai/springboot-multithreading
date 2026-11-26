package com.uma.executor.api.springbootmultithreading.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.Executor;

@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private final Executor taskExecutor;

    //constructor or use @RequiredArgConstructor at class level and comment this constructor
    /*
    Why @Qualifier("taskExecutor") Matters:
    • Avoids bean conflicts: Spring Boot often auto-configures a default applicationTaskExecutor bean. Using @Qualifier("taskExecutor") guarantees AsyncConfig grabs your custom userThread- pool rather than throwing a NoUniqueBeanDefinitionException.
    */
    public AsyncConfig(@Qualifier("taskExecutor") Executor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return taskExecutor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (Throwable ex, Method method, Object... params) ->
                log.error("Async exception in method: {} with parameters: {}",
                        method.getName(), Arrays.toString(params), ex);
    }
}

