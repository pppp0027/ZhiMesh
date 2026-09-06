package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pppp.zhimesh.common.entity.LLMCallRecord;
import com.pppp.zhimesh.common.mapper.LLMCallRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * LLM 调用记录 Service | LLM call record service
 * <p>Records are written in the owning worker/transaction so they cannot be
 * silently lost from an in-memory async queue during shutdown.</p>
 */
@Slf4j
@Service
public class LLMCallRecordService extends ServiceImpl<LLMCallRecordMapper, LLMCallRecord> {

    /**
     * 保存 LLM 调用记录 | Save an LLM call record in the owning worker.
     */
    public void saveRecord(LLMCallRecord record) {
        save(record);
    }

    /**
     * 根据 sourceType 和 sourceId 列表查询 | Query by source type and source IDs
     */
    public List<LLMCallRecord> listBySource(Integer sourceType, Collection<Long> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return Collections.emptyList();
        }
        return this.lambdaQuery()
                .eq(LLMCallRecord::getSourceType, sourceType)
                .in(LLMCallRecord::getSourceId, sourceIds)
                .list();
    }
}
