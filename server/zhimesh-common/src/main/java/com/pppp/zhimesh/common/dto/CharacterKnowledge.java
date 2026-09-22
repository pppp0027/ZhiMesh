package com.pppp.zhimesh.common.dto;

import lombok.Data;

/**
 * 会话中使用的知识库信息<br/>
 * 如果是其他人的知识库，并且当前用户已无读取权限（团队退出/企业库收窄），则返回的{kbInfo}为null,isEnable为false
 */
@Data
public class CharacterKnowledge {
    private Long id;
    private String uuid;
    private String title;
    private Boolean isMine;
    private KbInfoResp kbInfo;
    private Boolean isEnable;
    /** System KB content is read-only for users; only the role binding controls access. */
    private Boolean isSystem;
    private Boolean isReadOnly;
}
