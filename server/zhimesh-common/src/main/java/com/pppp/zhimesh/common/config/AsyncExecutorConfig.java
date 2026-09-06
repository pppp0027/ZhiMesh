package com.pppp.zhimesh.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded executors sized for the project's 4-core/4-GB single-instance deployment.
 * Every business workload has an explicit isolation boundary; queues stay deliberately
 * small so overload is visible and handled instead of consuming request threads or heap.
 */
@Slf4j
@Configuration
public class AsyncExecutorConfig {

    @Bean(name = {"chatExecutor", "applicationTaskExecutor", "taskExecutor"})
    @Primary
    public AsyncTaskExecutor chatExecutor(ZhiMeshProperties properties) {
        return executor("chat-", properties.getAsync().getChat());
    }

    @Bean(name = "ragRetrievalExecutor")
    public AsyncTaskExecutor ragRetrievalExecutor(ZhiMeshProperties properties) {
        ZhiMeshProperties.Executor settings = new ZhiMeshProperties.Executor();
        int concurrency = Math.max(1, properties.getRetrieval().getConcurrency());
        settings.setCorePoolSize(concurrency);
        settings.setMaxPoolSize(concurrency);
        settings.setQueueCapacity(Math.max(0, properties.getRetrieval().getQueueCapacity()));
        return executor("rag-retrieval-", settings);
    }

    @Bean(name = "backgroundExecutor")
    public AsyncTaskExecutor backgroundExecutor(ZhiMeshProperties properties) {
        return executor("background-", properties.getAsync().getBackground());
    }

    @Bean(name = "indexingExecutor")
    public AsyncTaskExecutor indexingExecutor(ZhiMeshProperties properties) {
        return executor("indexing-", properties.getAsync().getIndexing());
    }

    @Bean(name = "workflowExecutor")
    public AsyncTaskExecutor workflowExecutor(ZhiMeshProperties properties) {
        return executor("workflow-", properties.getAsync().getWorkflow());
    }

    @Bean(name = "imagesExecutor")
    public AsyncTaskExecutor imagesExecutor(ZhiMeshProperties properties) {
        return executor("image-", properties.getAsync().getImages());
    }

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(ZhiMeshProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, properties.getAsync().getTaskSchedulerPoolSize()));
        scheduler.setThreadNamePrefix("scheduled-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        scheduler.setErrorHandler(error -> log.error("Scheduled task failed", error));
        return scheduler;
    }

    @Bean(name = "lockRenewScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService lockRenewScheduler(ZhiMeshProperties properties) {
        int poolSize = Math.max(1, properties.getAsync().getLockRenewSchedulerPoolSize());
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(
                poolSize, namedDaemonFactory("lock-renew-"), new ThreadPoolExecutor.AbortPolicy());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    private AsyncTaskExecutor executor(String threadNamePrefix, ZhiMeshProperties.Executor settings) {
        int corePoolSize = Math.max(1, settings.getCorePoolSize());
        int maxPoolSize = Math.max(corePoolSize, settings.getMaxPoolSize());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(Math.max(0, settings.getQueueCapacity()));
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        log.info("Configuring executor {} core:{}, max:{}, queue:{}",
                threadNamePrefix, corePoolSize, maxPoolSize, settings.getQueueCapacity());
        return executor;
    }

    private ThreadFactory namedDaemonFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, error) ->
                    log.error("Uncaught exception in {}", thread.getName(), error));
            return thread;
        };
    }
}
