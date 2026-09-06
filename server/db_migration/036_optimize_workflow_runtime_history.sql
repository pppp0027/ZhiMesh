-- Lightweight workflow history queries and cooperative cancellation states.
-- Execute once after deploying the matching backend code.
BEGIN;

DROP INDEX IF EXISTS idx_wf_runtime_open_update_time;
CREATE INDEX idx_wf_runtime_open_update_time
    ON adi_workflow_runtime (update_time)
    WHERE status IN (1, 2, 5, 6);

CREATE INDEX IF NOT EXISTS idx_wf_runtime_workflow_user_update
    ON adi_workflow_runtime (workflow_id, user_id, update_time DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_wf_runtime_workflow_update
    ON adi_workflow_runtime (workflow_id, update_time DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_wf_runtime_node_runtime_id
    ON adi_workflow_runtime_node (workflow_runtime_id, id);

COMMENT ON COLUMN adi_workflow_runtime.status IS
    'Execution status: 1=Ready, 2=In progress, 3=Success, 4=Failed, 5=Waiting for input, 6=Cancel requested, 7=Cancelled';
COMMENT ON COLUMN adi_workflow_runtime_node.status IS
    'Execution status: 1=Ready, 2=In progress, 3=Success, 4=Failed, 5=Cancelled';

COMMIT;
