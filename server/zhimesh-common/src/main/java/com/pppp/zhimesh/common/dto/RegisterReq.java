package com.pppp.zhimesh.common.dto;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

@Schema(name = "注册请求参数 | Registration Request Parameters")
@Data
public class RegisterReq {

    @Parameter(description = "邮箱 | Email")
    @NotBlank(message = "Email cannot be empty")
    @Email
    private String email;

    @Parameter(description = "密码 | Password")
    @NotBlank(message = "Password cannot be empty")
    @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters")
    private String password;

    @Parameter(description = "验证码ID | Captcha ID")
    @NotBlank(message = "Captcha ID cannot be empty")
    @Length(min = 32, max = 64)
    private String captchaId;

    @Parameter(description = "验证码 | Captcha Code")
    @NotBlank(message = "Captcha code cannot be empty")
    @Length(min = 4, max = 6)
    private String captchaCode;
}
