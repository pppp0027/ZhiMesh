package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.dto.mcp.McpCommonParam;
import com.pppp.zhimesh.common.dto.mcp.McpCustomizedParamDefinition;
import com.pppp.zhimesh.common.dto.mcp.UserMcpCustomizedParam;
import com.pppp.zhimesh.common.entity.Mcp;
import com.pppp.zhimesh.common.entity.UserMcp;
import com.pppp.zhimesh.common.vo.McpValidationResult;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates MCP parameter names and encryption metadata without logging values. */
@Component
public class McpRuntimeConfigValidator {

    public McpValidationResult validateForStorage(Mcp mcp, List<UserMcpCustomizedParam> parameters) {
        return validate(mcp, parameters, false);
    }

    public McpValidationResult validateForRuntime(Mcp mcp, UserMcp userMcp) {
        List<UserMcpCustomizedParam> parameters = userMcp == null
                ? Collections.emptyList()
                : safe(userMcp.getMcpCustomizedParams());
        return validate(mcp, parameters, true);
    }

    private McpValidationResult validate(Mcp mcp, List<UserMcpCustomizedParam> parameters, boolean storedValues) {
        List<McpCustomizedParamDefinition> definitions = safe(mcp.getCustomizedParamDefinitions());
        Map<String, McpCustomizedParamDefinition> definitionByName = new LinkedHashMap<>();
        for (McpCustomizedParamDefinition definition : definitions) {
            if (definition != null && StringUtils.isNotBlank(definition.getName())) {
                definitionByName.putIfAbsent(definition.getName(), definition);
            }
        }

        Set<String> presetNames = new LinkedHashSet<>();
        for (McpCommonParam preset : safe(mcp.getPresetParams())) {
            if (preset != null && StringUtils.isNotBlank(preset.getName())) {
                presetNames.add(preset.getName());
            }
        }

        Map<String, UserMcpCustomizedParam> parameterByName = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        for (UserMcpCustomizedParam parameter : safe(parameters)) {
            if (parameter == null || StringUtils.isBlank(parameter.getName())) {
                unknown.add("<blank>");
                continue;
            }
            if (!definitionByName.containsKey(parameter.getName()) || parameterByName.containsKey(parameter.getName())) {
                unknown.add(parameter.getName());
                continue;
            }
            parameterByName.put(parameter.getName(), parameter);
        }

        Set<String> missing = new LinkedHashSet<>();
        Set<String> invalidEncryption = new LinkedHashSet<>();
        Set<String> conflicts = new LinkedHashSet<>();
        for (Map.Entry<String, McpCustomizedParamDefinition> entry : definitionByName.entrySet()) {
            String name = entry.getKey();
            UserMcpCustomizedParam parameter = parameterByName.get(name);
            if (parameter == null || parameter.getValue() == null
                    || StringUtils.isBlank(String.valueOf(parameter.getValue()))) {
                missing.add(name);
                continue;
            }
            boolean mustEncrypt = Boolean.TRUE.equals(entry.getValue().getRequireEncrypt());
            boolean markedEncrypted = Boolean.TRUE.equals(parameter.getEncrypted());
            if ((storedValues && mustEncrypt != markedEncrypted) || (!storedValues && markedEncrypted)) {
                invalidEncryption.add(name);
            }
            if (presetNames.contains(name)) {
                conflicts.add(name);
            }
        }

        return new McpValidationResult(
                List.copyOf(missing),
                List.copyOf(unknown),
                List.copyOf(invalidEncryption),
                List.copyOf(conflicts));
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }
}
