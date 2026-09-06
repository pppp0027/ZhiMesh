package com.pppp.zhimesh.common.memory.shortterm;

/**
 * Token budget assigned to the message window used by short-term memory.
 */
public record ShortTermMemoryTokenBudget(
        int messageWindowTokens,
        int historyTokens,
        int fixedMessageTokens,
        int toolTokens,
        int reservedOutputTokens,
        int safetyTokens,
        boolean overcommitted) {

    public static ShortTermMemoryTokenBudget calculate(int modelMaxInputTokens,
                                                        int configuredMaxHistoryTokens,
                                                        int fixedMessageTokens,
                                                        int toolTokens,
                                                        int reservedOutputTokens,
                                                        double safetyRatio) {
        int modelLimit = Math.max(1, modelMaxInputTokens);
        int fixed = Math.max(0, fixedMessageTokens);
        int tools = Math.max(0, toolTokens);
        int output = Math.max(0, reservedOutputTokens);
        double safeRatio = Math.max(0D, Math.min(0.5D, safetyRatio));
        int safety = (int) Math.ceil(modelLimit * safeRatio);

        int availableForMessages = Math.max(1, modelLimit - tools - output - safety);
        int availableForHistory = Math.max(0, availableForMessages - fixed);
        int configuredHistory = configuredMaxHistoryTokens > 0
                ? configuredMaxHistoryTokens
                : availableForHistory;
        int history = Math.min(configuredHistory, availableForHistory);
        int messageWindow = Math.max(1, Math.min(modelLimit, fixed + history));

        return new ShortTermMemoryTokenBudget(
                messageWindow,
                history,
                fixed,
                tools,
                output,
                safety,
                fixed > availableForMessages);
    }
}
