package com.pppp.zhimesh.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncExecutorConfigTest {

    @Test
    void exposesChatExecutorAsTheMvcAndAsyncDefault() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(ZhiMeshProperties.class, AsyncExecutorConfig.class);
            context.refresh();

            AsyncTaskExecutor chat = context.getBean("chatExecutor", AsyncTaskExecutor.class);
            assertThat(context.getBean("applicationTaskExecutor")).isSameAs(chat);
            assertThat(context.getBean("taskExecutor")).isSameAs(chat);
            assertThat(context.getBean(AsyncTaskExecutor.class)).isSameAs(chat);
            assertThat(context.getBean("taskScheduler")).isInstanceOf(ThreadPoolTaskScheduler.class);
            assertThat(context.getBean("lockRenewScheduler"))
                    .isInstanceOf(ScheduledExecutorService.class);
        }
    }

    @Test
    void appliesFourCoreFourGigabyteExecutorDefaults() {
        ZhiMeshProperties properties = new ZhiMeshProperties();
        AsyncExecutorConfig config = new AsyncExecutorConfig();

        ThreadPoolTaskExecutor chat = (ThreadPoolTaskExecutor) config.chatExecutor(properties);
        ThreadPoolTaskExecutor rag = (ThreadPoolTaskExecutor) config.ragRetrievalExecutor(properties);
        ThreadPoolTaskExecutor indexing = (ThreadPoolTaskExecutor) config.indexingExecutor(properties);
        ThreadPoolTaskExecutor background = (ThreadPoolTaskExecutor) config.backgroundExecutor(properties);
        ThreadPoolTaskExecutor workflow = (ThreadPoolTaskExecutor) config.workflowExecutor(properties);
        ThreadPoolTaskExecutor images = (ThreadPoolTaskExecutor) config.imagesExecutor(properties);
        ThreadPoolTaskScheduler scheduler = config.taskScheduler(properties);
        ScheduledExecutorService lockRenew = config.lockRenewScheduler(properties);

        try {
            assertExecutor(chat, 4, 8, 20);
            assertExecutor(rag, 4, 4, 8);
            assertExecutor(indexing, 2, 2, 50);
            assertExecutor(background, 1, 2, 20);
            assertExecutor(workflow, 1, 2, 10);
            assertExecutor(images, 1, 2, 10);
            assertThat(scheduler.getPoolSize()).isEqualTo(2);
            assertThat(((ScheduledThreadPoolExecutor) lockRenew).getCorePoolSize()).isEqualTo(2);
        } finally {
            List.of(chat, rag, indexing, background, workflow, images)
                    .forEach(ThreadPoolTaskExecutor::shutdown);
            scheduler.shutdown();
            lockRenew.shutdownNow();
        }
    }

    private void assertExecutor(ThreadPoolTaskExecutor executor, int core, int max, int queue) {
        assertThat(executor.getCorePoolSize()).isEqualTo(core);
        assertThat(executor.getMaxPoolSize()).isEqualTo(max);
        assertThat(executor.getQueueCapacity()).isEqualTo(queue);
    }
}
