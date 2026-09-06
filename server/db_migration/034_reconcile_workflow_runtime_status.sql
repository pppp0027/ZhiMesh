-- Repair workflow runs left in READY/DOING by the legacy runtime state machine.
-- Safe to re-run: every statement only touches non-terminal status 1/2.
BEGIN;

-- A failed child node proves the workflow failed.
UPDATE adi_workflow_runtime runtime
SET status = 4,
    status_remark = CASE
        WHEN COALESCE(runtime.status_remark, '') = '' THEN '历史状态修复：工作流节点执行失败'
        ELSE runtime.status_remark
    END
WHERE runtime.status IN (1, 2)
  AND EXISTS (
      SELECT 1
      FROM adi_workflow_runtime_node node
      WHERE node.workflow_runtime_id = runtime.id
        AND node.status = 4
  );

-- A successful explicit End or graph-leaf node proves the graph reached a valid
-- terminal. Merely
-- seeing that all currently persisted children succeeded is not enough: the old
-- process may have crashed before it created the next child.
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
  );

-- Remaining non-terminal rows older than six SSE lifetimes cannot still have
-- a live client execution and are conservatively classified as interrupted.
UPDATE adi_workflow_runtime
SET status = 4,
    status_remark = '历史状态修复：执行已中断或后端曾重启'
WHERE status IN (1, 2)
  AND update_time < CURRENT_TIMESTAMP - INTERVAL '30 minutes';

-- Keep child details consistent with parents classified as interrupted.
UPDATE adi_workflow_runtime_node node
SET status = 4,
    status_remark = CASE
        WHEN COALESCE(node.status_remark, '') = '' THEN '历史状态修复：父工作流执行已中断'
        ELSE node.status_remark
    END
WHERE node.status IN (1, 2)
  AND EXISTS (
      SELECT 1
      FROM adi_workflow_runtime runtime
      WHERE runtime.id = node.workflow_runtime_id
        AND runtime.status = 4
        AND runtime.status_remark = '历史状态修复：执行已中断或后端曾重启'
  );

-- Supports the runtime heartbeat/recovery job without scanning historical runs.
CREATE INDEX IF NOT EXISTS idx_wf_runtime_open_update_time
    ON adi_workflow_runtime (update_time)
    WHERE status IN (1, 2, 5);

COMMENT ON COLUMN adi_workflow_runtime.status IS
    'Execution status: 1=Ready, 2=In progress, 3=Success, 4=Failed, 5=Waiting for input';
COMMENT ON COLUMN adi_workflow_runtime_node.status IS
    'Execution status: 1=Ready, 2=In progress, 3=Success, 4=Failed';

COMMIT;
