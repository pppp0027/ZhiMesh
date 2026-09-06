package com.pppp.zhimesh.common.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.pppp.zhimesh.common.interfaces.AbstractSearchEngineService;
import lombok.Data;

@Data
public class SearchEngineInfo {
    private String name;
    private Boolean enable;
    @JsonIgnore
    private AbstractSearchEngineService searchEngineService;
}
