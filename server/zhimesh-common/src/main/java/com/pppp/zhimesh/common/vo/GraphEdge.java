package com.pppp.zhimesh.common.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class GraphEdge {
    private String id;
    private String label;
    private Double weight;
    private String description;
    @JsonProperty("relation_type")
    private String relationType;
    private Boolean polarity;
    private String status;
    private Map<String, Object> properties;
    @JsonProperty("evidence_count")
    private Integer evidenceCount;
    @JsonProperty("text_segment_id")
    private String textSegmentId;
    private Map<String, Object> metadata;

    //Source vertex
    private String startId;
    private String sourceName;
    private Map<String, Object> sourceMetadata;

    //Target vertex
    private String endId;
    private String targetName;
    private Map<String, Object> targetMetadata;
}
