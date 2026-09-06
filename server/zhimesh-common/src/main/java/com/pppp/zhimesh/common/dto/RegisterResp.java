package com.pppp.zhimesh.common.dto;

import lombok.Data;

/**
 * Registration result. In demonstration mode, {@code autoLogin} is true and
 * {@code login} contains the session that can be applied by the client.
 */
@Data
public class RegisterResp {

    private boolean autoLogin;

    private String message;

    private LoginResp login;
}
