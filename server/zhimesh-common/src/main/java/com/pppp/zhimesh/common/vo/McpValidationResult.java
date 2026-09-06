package com.pppp.zhimesh.common.vo;

import java.util.List;

/**
 * MCP configuration validation result. Only parameter names are retained so
 * diagnostics never expose decrypted values.
 */
public record McpValidationResult(
        List<String> missingParameters,
        List<String> unknownParameters,
        List<String> invalidEncryptionParameters,
        List<String> conflictingPresetParameters) {

    public boolean valid() {
        return missingParameters.isEmpty()
                && unknownParameters.isEmpty()
                && invalidEncryptionParameters.isEmpty()
                && conflictingPresetParameters.isEmpty();
    }
}
