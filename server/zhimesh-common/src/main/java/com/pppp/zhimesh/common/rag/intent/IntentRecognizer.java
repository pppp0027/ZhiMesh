package com.pppp.zhimesh.common.rag.intent;

/** Strategy boundary for rules, prototypes and future trained classifiers. */
public interface IntentRecognizer {
    IntentDecision recognize(IntentRoutingContext context);
}
