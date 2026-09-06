package com.pppp.zhimesh.common.rag.intent;

import com.pppp.zhimesh.common.dto.KbInfoResp;
import dev.langchain4j.data.embedding.Embedding;

import java.util.List;
import java.util.Map;

/** Low-latency profile comparison; it must never query the real chunk vector store. */
public interface KnowledgeScopeProfileProbe {
    /**
     * Compares all safe query variants against one Redis bundle read. The
     * highest score protects a contextual rewrite from lowering recall.
     */
    Result probe(List<Embedding> queryEmbeddings, List<KbInfoResp> knowledgeBases);

    record Result(boolean complete, boolean containsStaleProfile, double maxScore,
                  int comparedProfiles, String reason, Map<String, KnowledgeBaseMatch> matches) {
        public Result(boolean complete, boolean containsStaleProfile, double maxScore,
                      int comparedProfiles, String reason) {
            this(complete, containsStaleProfile, maxScore, comparedProfiles, reason, Map.of());
        }

        public Result {
            matches = matches == null ? Map.of() : Map.copyOf(matches);
        }

        public static Result incomplete(String reason) {
            return new Result(false, false, -1D, 0, reason, Map.of());
        }

        /**
         * Per-knowledge-base vector evidence. A match can be incomplete while
         * other knowledge bases in the same request remain safely evaluable.
         */
        record KnowledgeBaseMatch(boolean complete, boolean containsStaleProfile,
                                  double maxScore, double topKMeanScore,
                                  int comparedProfiles, String reason) {
            static KnowledgeBaseMatch incomplete(String reason) {
                return new KnowledgeBaseMatch(false, false, -1D, -1D, 0, reason);
            }
        }
    }
}
