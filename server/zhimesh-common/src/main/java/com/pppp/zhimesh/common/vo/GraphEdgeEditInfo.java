package com.pppp.zhimesh.common.vo;

import lombok.Data;

@Data
public class GraphEdgeEditInfo {
    private GraphEdge edge;

    private GraphSearchCondition sourceFilter;
    private GraphSearchCondition targetFilter;
}
