package com.pppp.zhimesh.common.dto;

import com.pppp.zhimesh.common.vo.GraphEdge;
import com.pppp.zhimesh.common.vo.GraphVertex;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefGraphDto {
    private List<String> entitiesFromQuestion;
    private List<GraphVertex> vertices;
    private List<GraphEdge> edges;
}
