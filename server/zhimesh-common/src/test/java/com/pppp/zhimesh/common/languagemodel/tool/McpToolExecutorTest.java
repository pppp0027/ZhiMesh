package com.pppp.zhimesh.common.languagemodel.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolExecutorTest {

    @Test
    void delegatesExecutionToWrappedMcpClient() throws Exception {
        ToolSpecification spec = ToolSpecification.builder().name("weather").description("get weather").build();
        McpClient client = mock(McpClient.class);
        when(client.executeTool(any(ToolExecutionRequest.class)))
                .thenReturn(ToolExecutionResult.builder().resultText("sunny").build());
        McpToolExecutor executor = new McpToolExecutor(spec, client);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("id-1").name("weather").arguments("{\"city\":\"广州\"}").build();

        assertThat(executor.spec()).isSameAs(spec);
        assertThat(executor.isMcpTool()).isTrue();
        assertThat(executor.execute(request, new ToolContext())).isEqualTo("sunny");
        verify(client).executeTool(request);
    }

    @Test
    void propagatesClientFailureToCallingLoop() {
        ToolSpecification spec = ToolSpecification.builder().name("weather").description("get weather").build();
        McpClient client = mock(McpClient.class);
        when(client.executeTool(any(ToolExecutionRequest.class))).thenThrow(new RuntimeException("mcp down"));
        McpToolExecutor executor = new McpToolExecutor(spec, client);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("id-1").name("weather").arguments("{}").build();

        assertThatThrownBy(() -> executor.execute(request, new ToolContext()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("mcp down");
    }
}
