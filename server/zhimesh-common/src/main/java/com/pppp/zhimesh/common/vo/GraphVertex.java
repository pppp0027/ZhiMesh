package com.pppp.zhimesh.common.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GraphVertex {

    private String id;
//Apache AGE does not support multiple labels yet
    //Apache AGE暂时不支持多标签
    private String label;
    private String name;
    @JsonProperty("canonical_name")
    private String canonicalName;
    private List<String> aliases;
    private Map<String, Object> properties;
    private Double salience;

    /**
     * 如对应的文本段id
     */
    @JsonProperty("text_segment_id")
    private String textSegmentId;
    private String description;

    /**
     * 如 kb_uuid=>123,kb_item_uuid=>'22222,3333'
     */
    private Map<String, Object> metadata;
}
