package com.pppp.zhimesh.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pppp.zhimesh.common.entity.WorkflowRuntimeNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WorkflowRuntimeNodeMapper extends BaseMapper<WorkflowRuntimeNode> {

    @Update("""
            UPDATE adi_workflow_runtime_node node
            SET status = 4,
                status_remark = CASE
                    WHEN COALESCE(node.status_remark, '') = '' THEN '执行中断：父工作流心跳超时'
                    ELSE node.status_remark
                END
            WHERE node.status IN (1, 2)
              AND EXISTS (
                  SELECT 1
                  FROM adi_workflow_runtime runtime
                  WHERE runtime.id = node.workflow_runtime_id
                    AND runtime.status = 4
                    AND runtime.status_remark = '执行中断：后端进程已停止或执行心跳超时'
              )
            """)
    int failNodesOfStaleRuntimes();

    @Update("""
            UPDATE adi_workflow_runtime_node node
            SET status = 5,
                status_remark = '工作流已取消'
            WHERE node.status IN (1, 2)
              AND EXISTS (
                  SELECT 1
                  FROM adi_workflow_runtime runtime
                  WHERE runtime.id = node.workflow_runtime_id
                    AND runtime.status = 7
              )
            """)
    int cancelNodesOfCancelledRuntimes();
}
