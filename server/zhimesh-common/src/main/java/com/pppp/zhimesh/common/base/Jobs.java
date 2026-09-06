package com.pppp.zhimesh.common.base;

import com.pppp.zhimesh.common.service.KnowledgeBaseService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class Jobs {

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @Scheduled(fixedDelay = 60 * 1000)
    public void run() {
        knowledgeBaseService.updateStatistic();
    }
}
