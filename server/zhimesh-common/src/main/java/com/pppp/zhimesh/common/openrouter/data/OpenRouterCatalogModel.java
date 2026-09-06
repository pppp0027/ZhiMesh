package com.pppp.zhimesh.common.openrouter.data;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class OpenRouterCatalogModel {
    private String id;
    private String canonicalSlug;
    private String displayName;
    private String description;
    private Integer contextLength;
    private Integer topProviderContextLength;
    private String expirationDate;
    private String detailsPath;
    private List<String> inputModalities;
    private List<String> outputModalities;
    private List<String> supportedParameters;
    private Map<String, String> pricing;
    private ObjectNode rawMetadata;
}
