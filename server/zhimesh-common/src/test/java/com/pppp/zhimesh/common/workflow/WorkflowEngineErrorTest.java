package com.pppp.zhimesh.common.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowEngineErrorTest {

    @Test
    void usesRootCauseMessage() {
        Throwable error = new RuntimeException("wrapper", new IllegalStateException("root failure"));

        assertEquals("root failure", WorkflowEngine.safeErrorMessage(error));
    }

    @Test
    void handlesThrowableWithoutMessage() {
        assertEquals("RuntimeException", WorkflowEngine.safeErrorMessage(new RuntimeException()));
    }

    @Test
    void handlesMissingThrowable() {
        assertEquals("Workflow execution failed", WorkflowEngine.safeErrorMessage(null));
    }
}
