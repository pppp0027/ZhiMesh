package com.pppp.zhimesh.common.workflow;

/** Internal control-flow signal for cooperative workflow cancellation. */
public class WorkflowCancelledException extends RuntimeException {

    public WorkflowCancelledException() {
        super("Workflow execution cancelled");
    }
}
