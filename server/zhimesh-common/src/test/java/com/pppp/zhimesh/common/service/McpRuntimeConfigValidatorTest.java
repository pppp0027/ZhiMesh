package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.dto.mcp.McpCommonParam;
import com.pppp.zhimesh.common.dto.mcp.McpCustomizedParamDefinition;
import com.pppp.zhimesh.common.dto.mcp.UserMcpCustomizedParam;
import com.pppp.zhimesh.common.entity.Mcp;
import com.pppp.zhimesh.common.entity.UserMcp;
import com.pppp.zhimesh.common.vo.McpValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpRuntimeConfigValidatorTest {

    private final McpRuntimeConfigValidator validator = new McpRuntimeConfigValidator();

    @Test
    void acceptsCompleteStoredConfiguration() {
        Mcp mcp = mcp(definition("token", true), definition("region", false));
        UserMcp userMcp = new UserMcp();
        userMcp.setMcpCustomizedParams(List.of(
                parameter("token", "cipher-text", true),
                parameter("region", "cn-east", false)));

        assertThat(validator.validateForRuntime(mcp, userMcp).valid()).isTrue();
    }

    @Test
    void reportsMissingUnknownAndEncryptionProblemsWithoutValues() {
        Mcp mcp = mcp(definition("token", true), definition("region", false));
        UserMcp userMcp = new UserMcp();
        userMcp.setMcpCustomizedParams(List.of(
                parameter("token", "plain-text", false),
                parameter("other", "must-not-appear-in-result", false)));

        McpValidationResult result = validator.validateForRuntime(mcp, userMcp);

        assertThat(result.valid()).isFalse();
        assertThat(result.missingParameters()).containsExactly("region");
        assertThat(result.unknownParameters()).containsExactly("other");
        assertThat(result.invalidEncryptionParameters()).containsExactly("token");
        assertThat(result.toString()).doesNotContain("plain-text", "must-not-appear-in-result");
    }

    @Test
    void preventsCustomizedParametersFromOverridingPresetValues() {
        Mcp mcp = mcp(definition("token", true));
        McpCommonParam preset = new McpCommonParam();
        preset.setName("token");
        preset.setValue("admin-value");
        mcp.setPresetParams(List.of(preset));

        McpValidationResult result = validator.validateForStorage(
                mcp, List.of(parameter("token", "user-value", false)));

        assertThat(result.conflictingPresetParameters()).containsExactly("token");
    }

    private static Mcp mcp(McpCustomizedParamDefinition... definitions) {
        Mcp mcp = new Mcp();
        mcp.setCustomizedParamDefinitions(List.of(definitions));
        mcp.setPresetParams(List.of());
        return mcp;
    }

    private static McpCustomizedParamDefinition definition(String name, boolean encrypted) {
        McpCustomizedParamDefinition definition = new McpCustomizedParamDefinition();
        definition.setName(name);
        definition.setRequireEncrypt(encrypted);
        return definition;
    }

    private static UserMcpCustomizedParam parameter(String name, Object value, boolean encrypted) {
        UserMcpCustomizedParam parameter = new UserMcpCustomizedParam();
        parameter.setName(name);
        parameter.setValue(value);
        parameter.setEncrypted(encrypted);
        return parameter;
    }
}
