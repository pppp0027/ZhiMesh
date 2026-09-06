package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.Mcp;
import com.pppp.zhimesh.common.entity.UserMcp;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserMcpServiceUrlTest {

    @Test
    void encodesQueryNamesAndValues() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("access token", "中文 & value=1");

        String result = UserMcpService.appendQueryParameters("https://example.test/sse", parameters);

        assertThat(result).isEqualTo(
                "https://example.test/sse?access+token=%E4%B8%AD%E6%96%87+%26+value%3D1");
    }

    @Test
    void preservesExistingQueryString() {
        String result = UserMcpService.appendQueryParameters(
                "https://example.test/sse?version=1", Map.of("region", "cn east"));

        assertThat(result).isEqualTo("https://example.test/sse?version=1&region=cn+east");
    }

    @Test
    void createsLegacySseTransport() {
        Mcp mcp = mcp(ZhiMeshConstant.McpConstant.TRANSPORT_TYPE_SSE);
        mcp.setSseUrl("https://example.test/sse");

        McpTransport transport = new UserMcpService().createTransport(mcp, userMcp());

        assertThat(transport).isInstanceOf(HttpMcpTransport.class);
    }

    @Test
    void createsStreamableHttpTransport() {
        Mcp mcp = mcp(ZhiMeshConstant.McpConstant.TRANSPORT_TYPE_STREAMABLE_HTTP);
        mcp.setStreamableHttpUrl("https://example.test/mcp");

        McpTransport transport = new UserMcpService().createTransport(mcp, userMcp());

        assertThat(transport).isInstanceOf(StreamableHttpMcpTransport.class);
    }

    @Test
    void createsStdioTransport() {
        Mcp mcp = mcp(ZhiMeshConstant.McpConstant.TRANSPORT_TYPE_STDIO);
        mcp.setStdioCommand("npx");
        mcp.setStdioArg("server-package");

        McpTransport transport = new UserMcpService().createTransport(mcp, userMcp());

        assertThat(transport).isInstanceOf(StdioMcpTransport.class);
    }

    @Test
    void rejectsUnknownTransportInsteadOfTreatingItAsStdio() {
        Mcp mcp = mcp("unknown");

        assertThatThrownBy(() -> new UserMcpService().createTransport(mcp, userMcp()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported MCP transport type: unknown");
    }

    private static Mcp mcp(String transportType) {
        Mcp mcp = new Mcp();
        mcp.setTransportType(transportType);
        mcp.setSseTimeout(30);
        return mcp;
    }

    private static UserMcp userMcp() {
        return new UserMcp();
    }
}
