package com.pppp.zhimesh.common.rag.intent;

/** Route decision returned to callers; proposed and effective are equal in direct mode. */
public record RetrievalPlan(IntentDecision decision,
                            RetrievalSelection proposed,
                            RetrievalSelection effective,
                            boolean fallback,
                            String reason) {
}
