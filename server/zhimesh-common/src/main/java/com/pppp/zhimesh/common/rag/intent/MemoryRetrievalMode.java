package com.pppp.zhimesh.common.rag.intent;

/** Long-term memory channels allowed for one request. */
public enum MemoryRetrievalMode {
    NONE(false, false),
    SEMANTIC(true, false),
    EPISODIC(false, true),
    BOTH(true, true),
    /** High-recall probe used when memory need is plausible but not explicit. */
    AUTO(true, true);

    private final boolean semantic;
    private final boolean episodic;

    MemoryRetrievalMode(boolean semantic, boolean episodic) {
        this.semantic = semantic;
        this.episodic = episodic;
    }

    public boolean includesSemantic() {
        return semantic;
    }

    public boolean includesEpisodic() {
        return episodic;
    }

    public boolean isAuto() {
        return this == AUTO;
    }
}
