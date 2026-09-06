package com.pppp.zhimesh.common.dto.evaluation;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class RagEvaluationCandidateResp {
    private String content;
    private String contentType;
    private List<String> routes;
    private List<String> sourceDocumentIds;
    private List<String> sourceDocumentNames;
    private List<String> sourceSegmentIds;
    private List<String> graphElementIds;
    private Double vectorScore;
    private Double bm25Score;
    private Integer vectorRank;
    private Integer graphRank;
    private Integer bm25Rank;
    private Map<String, Integer> routeRanks;
    private Double rrfScore;
    private Double rerankScore;
    private Integer tokenCount;
    private boolean selected;
}
