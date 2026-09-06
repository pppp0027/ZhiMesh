package com.pppp.zhimesh.common.dto.conversation;

public record ConversationBackfillResult(
        long scannedCount,
        long updatedCount,
        long skippedCount,
        long failedCount) {
}
