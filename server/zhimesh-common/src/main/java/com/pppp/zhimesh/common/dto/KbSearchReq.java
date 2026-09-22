package com.pppp.zhimesh.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class KbSearchReq {
    private String title;
    /** 企业库可见范围筛选 STAFF/EXECUTIVE（管理端）。 */
    private String companyScope;
    private Boolean isSystem;
    private Boolean isEnabled;
    private Integer minItemCount;
    private Integer minEmbeddingCount;
    private Long[] createTime;
    private Long[] updateTime;
    private String ownerName;
    /** 归属层级筛选 PERSONAL/TEAM/COMPANY（管理端）。 */
    private String ownerType;
}
