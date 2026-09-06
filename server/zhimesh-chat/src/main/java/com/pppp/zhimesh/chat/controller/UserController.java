package com.pppp.zhimesh.chat.controller;

import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.ConfigResp;
import com.pppp.zhimesh.common.dto.ModifyPasswordReq;
import com.pppp.zhimesh.common.dto.UserUpdateReq;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.service.UserService;
import com.pppp.zhimesh.common.util.UserAvatarPool;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static jakarta.servlet.http.HttpServletResponse.SC_FOUND;

@Slf4j
@Tag(name = "用户controller | User Controller")
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;

    @Operation(summary = "用户信息 | User Info")
    @GetMapping("/{uuid}")
    public void info(@Validated @PathVariable String uuid) {
        log.info(uuid);
    }

    @Operation(summary = "配置信息 | Config Info")
    @GetMapping("/config")
    public ConfigResp configInfo(HttpServletRequest request) {
        return userService.getConfig(request);
    }

    @Operation(summary = "更新信息 | Update Info")
    @PostMapping("/edit")
    public void update(@Validated @RequestBody UserUpdateReq userUpdateReq) {
        userService.updateConfig(userUpdateReq);
    }

    @Operation(summary = "修改密码 | Change Password")
    @PostMapping("/password/modify")
    public String modifyPassword(@RequestBody ModifyPasswordReq modifyPasswordReq) {
        userService.modifyPassword(modifyPasswordReq.getOldPassword(), modifyPasswordReq.getNewPassword());
        return "Password changed successfully";
    }

    @Operation(summary = "退出 | Logout")
    @PostMapping("/logout")
    public void logout() {
        userService.logout();
    }

    @Operation(summary = "当前用户头像 | Current User Avatar")
    @GetMapping(value = "/myAvatar", produces = MediaType.IMAGE_PNG_VALUE)
    public void myAvatar(HttpServletResponse response) {
        User user = ThreadContext.getCurrentUser();
        if (StringUtils.isBlank(user.getAvatar())) {
            user = userService.getByUserId(user.getId());
        }
        redirectToAvatar(user, user.getUuid(), response);
    }

    @Operation(summary = "用户头像 | User Avatar")
    @GetMapping(value = "/avatar/{uuid}", produces = MediaType.IMAGE_PNG_VALUE)
    public void avatar(@Validated @PathVariable String uuid, @RequestParam(defaultValue = "64") @Min(32) @Max(128) Integer width, @RequestParam(defaultValue = "64") @Min(32) @Max(128) Integer height, HttpServletResponse response) {
        User user = userService.getByUuid(uuid);
        redirectToAvatar(user, uuid, response);
    }

    private void redirectToAvatar(User user, String fallbackSeed, HttpServletResponse response) {
        String avatar = user != null && StringUtils.isNotBlank(user.getAvatar())
                ? user.getAvatar()
                : UserAvatarPool.avatarForSeed(fallbackSeed);
        // Keep Location relative so the browser loads the static avatar from the
        // user-facing origin (Vite in development, gateway in production).
        response.setStatus(SC_FOUND);
        response.setHeader("Location", avatar);
        // The mapping is stored in the database and may change. Cache the static
        // PNG itself, not this redirect decision.
        response.setHeader("Cache-Control", "no-store");
    }
}
