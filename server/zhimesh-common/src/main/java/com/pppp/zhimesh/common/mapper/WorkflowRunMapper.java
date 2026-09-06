package com.pppp.zhimesh.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pppp.zhimesh.common.entity.WorkflowRuntime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;

@Mapper
public interface WorkflowRunMapper extends BaseMapper<WorkflowRuntime> {

    @Update("""
            <script>
            UPDATE adi_workflow_runtime
            SET update_time = CURRENT_TIMESTAMP
            WHERE status IN (1, 2)
              AND id IN
              <foreach collection="runtimeIds" item="runtimeId" open="(" separator="," close=")">
                #{runtimeId}
              </foreach>
            </script>
            """)
    int heartbeatActive(@Param("runtimeIds") Collection<Long> runtimeIds);

    /** A failed child is conclusive evidence that its still-open parent run failed. */
    @Update("""
            UPDATE adi_workflow_runtime runtime
            SET status = 4,
                status_remark = CASE
                    WHEN COALESCE(runtime.status_remark, '') = '' THEN '工作流节点执行失败'
                    ELSE runtime.status_remark
                END
            WHERE runtime.status IN (1, 2)
              AND EXISTS (
                  SELECT 1
                  FROM adi_workflow_runtime_node node
                  WHERE node.workflow_runtime_id = runtime.id
                    AND node.status = 4
              )
            """)
    int reconcileFailedRuntimes();

    /** A successful explicit End or graph-leaf node proves the graph reached a valid terminal. */
    @Update("""
            UPDATE adi_workflow_runtime runtime
            SET status = 3,
                status_remark = ''
            WHERE runtime.status IN (1, 2)
              AND EXISTS (
                  SELECT 1
                  FROM adi_workflow_runtime_node runtime_node
                  JOIN adi_workflow_node definition_node
                    ON definition_node.id = runtime_node.node_id
                  JOIN adi_workflow_component component
                    ON component.id = definition_node.workflow_component_id
                  WHERE runtime_node.workflow_runtime_id = runtime.id
                    AND runtime_node.status = 3
                    AND (
                        component.name = 'End'
                        OR (
                            EXISTS (
                                SELECT 1
                                FROM adi_workflow_edge incoming_edge
                                WHERE incoming_edge.workflow_id = runtime.workflow_id
                                  AND incoming_edge.target_node_uuid = definition_node.uuid
                            )
                            AND NOT EXISTS (
                                SELECT 1
                                FROM adi_workflow_edge outgoing_edge
                                WHERE outgoing_edge.workflow_id = runtime.workflow_id
                                  AND outgoing_edge.source_node_uuid = definition_node.uuid
                            )
                        )
                    )
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM adi_workflow_runtime_node failed_node
                  WHERE failed_node.workflow_runtime_id = runtime.id
                    AND failed_node.status = 4
              )
            """)
    int reconcileCompletedRuntimes();

    @Update("""
            UPDATE adi_workflow_runtime
            SET status = 4,
                status_remark = '执行中断：后端进程已停止或执行心跳超时'
            WHERE status IN (1, 2)
              AND update_time < CURRENT_TIMESTAMP - (#{staleSeconds} * INTERVAL '1 second')
            """)
    int failStaleRuntimes(@Param("staleSeconds") int staleSeconds);

    @Update("""
            UPDATE adi_workflow_runtime
            SET status = 4,
                status_remark = '等待输入已失效，请重新运行工作流'
            WHERE status = 5
              AND update_time < CURRENT_TIMESTAMP - (#{staleSeconds} * INTERVAL '1 second')
            """)
    int expireStaleWaitingInput(@Param("staleSeconds") int staleSeconds);

    @Update("""
            UPDATE adi_workflow_runtime
            SET status = 7,
                status_remark = '工作流已取消'
            WHERE status = 6
              AND update_time < CURRENT_TIMESTAMP - (#{staleSeconds} * INTERVAL '1 second')
            """)
    int finalizeStaleCancellations(@Param("staleSeconds") int staleSeconds);
}
