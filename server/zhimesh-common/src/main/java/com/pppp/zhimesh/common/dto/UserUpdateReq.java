package com.pppp.zhimesh.common.dto;

import com.pppp.zhimesh.common.annotation.NotAllFieldsEmptyCheck;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@NotAllFieldsEmptyCheck
public class UserUpdateReq {
    @Pattern(regexp = "(?s).*\\S.*", message = "Display name cannot be empty")
    @Size(max = 45, message = "Display name must not exceed 45 characters")
    private String name;

    private String secretKey;
    private String locale;

    public void setName(String name) {
        this.name = name == null ? null : name.strip();
    }
}
