package com.pppp.zhimesh.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pppp.zhimesh.common.dto.UserAddReq;
import com.pppp.zhimesh.common.dto.UserEditReq;
import com.pppp.zhimesh.common.dto.UserInfoDto;
import com.pppp.zhimesh.common.dto.UserSearchReq;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.BeanUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/user")
@Validated
public class AdminUserController {

    @Resource
    private UserService userService;

    @PostMapping("/search")
    public Page<UserInfoDto> search(@RequestBody UserSearchReq userSearchReq, @NotNull @Min(1) Integer currentPage, @NotNull @Min(10) Integer pageSize) {
        return userService.search(userSearchReq, currentPage, pageSize);
    }

    @Operation(summary = "用户信息 | User Info")
    @GetMapping("/info/{uuid}")
    public UserInfoDto info(@PathVariable String uuid) {
        User user = userService.getByUuidOrThrow(uuid);
        UserInfoDto result = new UserInfoDto();
        BeanUtils.copyProperties(user, result);
        return result;
    }

    @PostMapping("/addOne")
    public UserInfoDto addOne(@Validated @RequestBody UserAddReq addUserReq) {
        return userService.addUser(addUserReq);
    }

    @PostMapping("/active/{uuid}")
    public void activeByUuid(@PathVariable String uuid) {
        userService.activeByUuid(uuid);
    }

    @PostMapping("/freeze/{uuid}")
    public void freezeByUuid(@PathVariable String uuid) {
        userService.freeze(uuid);
    }

    @PostMapping("/edit")
    public void editUser(@Validated @RequestBody UserEditReq userEditReq) {
        userService.editUser(userEditReq);
    }
}
