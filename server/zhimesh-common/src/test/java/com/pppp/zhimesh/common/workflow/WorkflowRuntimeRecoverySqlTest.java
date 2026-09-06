package com.pppp.zhimesh.common.workflow;

import com.pppp.zhimesh.common.mapper.WorkflowRunMapper;
import com.pppp.zhimesh.common.mapper.WorkflowRuntimeNodeMapper;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowRuntimeRecoverySqlTest {

    @Test
    void mapperAnnotationsCanBeParsedByMyBatis() {
        Configuration configuration = new Configuration();

        configuration.addMapper(WorkflowRunMapper.class);
        configuration.addMapper(WorkflowRuntimeNodeMapper.class);
    }

    @Test
    void completionRecoveryRequiresARealTerminalNode() throws Exception {
        String sql = updateSql(WorkflowRunMapper.class.getMethod("reconcileCompletedRuntimes"));

        assertTrue(sql.contains("component.name = 'End'"));
        assertTrue(sql.contains("incoming_edge.target_node_uuid = definition_node.uuid"));
        assertTrue(sql.contains("outgoing_edge.source_node_uuid = definition_node.uuid"));
        assertTrue(sql.contains("runtime_node.status = 3"));
    }

    @Test
    void recoverySqlRemainsCompatibleWithPhysicalDeleteSchema() {
        for (Method method : WorkflowRunMapper.class.getDeclaredMethods()) {
            Update update = method.getAnnotation(Update.class);
            if (update != null) {
                assertFalse(String.join("\n", update.value()).contains("is_deleted"), method.getName());
            }
        }
        for (Method method : WorkflowRuntimeNodeMapper.class.getDeclaredMethods()) {
            Update update = method.getAnnotation(Update.class);
            if (update != null) {
                assertFalse(String.join("\n", update.value()).contains("is_deleted"), method.getName());
            }
        }
    }

    private String updateSql(Method method) {
        return String.join("\n", method.getAnnotation(Update.class).value());
    }
}
