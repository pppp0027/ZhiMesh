package com.pppp.zhimesh.chat.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pppp.zhimesh.common.dto.TeamEditReq;
import com.pppp.zhimesh.common.dto.TeamInfoResp;
import com.pppp.zhimesh.common.dto.TeamMemberAddReq;
import com.pppp.zhimesh.common.dto.TeamMemberResp;
import com.pppp.zhimesh.common.dto.TeamMemberUpdateReq;
import com.pppp.zhimesh.common.entity.Team;
import com.pppp.zhimesh.common.service.TeamService;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 团队管理。全部端点要求登录态（/team 不在 TokenFilter 匿名白名单内）；
 * 管理权由 TeamService 按 OWNER 成员身份校验。
 */
@RestController
@RequestMapping("/team")
@Validated
public class TeamController {

    @Resource
    private TeamService teamService;

    /**
     * Search my teams
     * 搜索我的团队
     *
     * @param keyword     搜索关键词 / Search keyword
     * @param currentPage 当前页数 / Current page number
     * @param pageSize    每页数量 / Page size
     * @return 团队列表（含 myRole 与成员数）
     */
    @GetMapping("/my/search")
    public Page<TeamInfoResp> searchMyTeams(@RequestParam(defaultValue = "") String keyword,
                                            @NotNull @Min(1) Integer currentPage,
                                            @NotNull @Min(1) Integer pageSize) {
        return teamService.searchMyTeams(keyword, currentPage, pageSize);
    }

    /**
     * My teams lite list for create/transfer dialogs
     * 我的团队轻量列表（建库/转移弹窗用）
     */
    @GetMapping("/my/lite")
    public List<TeamInfoResp> myTeamsLite() {
        return teamService.myTeamsLite();
    }

    /**
     * Create or edit a team (owner only for edits)
     * 新建或编辑团队（编辑仅 OWNER）
     */
    @PostMapping("/saveOrUpdate")
    public Team saveOrUpdate(@RequestBody @Validated TeamEditReq teamEditReq) {
        return teamService.saveOrUpdate(teamEditReq);
    }

    /**
     * Delete a team; refused while it still owns knowledge bases
     * 删除团队（名下仍有团队知识库时拒绝）
     */
    @PostMapping("/del/{uuid}")
    public boolean delete(@PathVariable @NotBlank String uuid) {
        teamService.delete(uuid);
        return true;
    }

    /**
     * Team roster, visible to any member
     * 团队花名册（任一成员可见）
     */
    @GetMapping("/member/list")
    public List<TeamMemberResp> listMembers(@RequestParam @NotBlank String teamUuid) {
        return teamService.listMembers(teamUuid);
    }

    /**
     * Invite a member by email (owner only)
     * 按邮箱邀请成员（仅 OWNER）
     */
    @PostMapping("/member/add")
    public TeamMemberResp addMember(@RequestBody @Validated TeamMemberAddReq addReq) {
        return teamService.addMember(addReq);
    }

    /**
     * Update a member role (owner only, last-owner protected)
     * 调整成员角色（仅 OWNER，唯一 OWNER 不可降级）
     */
    @PostMapping("/member/updateRole")
    public boolean updateMemberRole(@RequestBody @Validated TeamMemberUpdateReq updateReq) {
        teamService.updateMemberRole(updateReq);
        return true;
    }

    /**
     * Remove a member (owner only)
     * 移除成员（仅 OWNER）
     */
    @PostMapping("/member/remove/{teamUuid}/{userId}")
    public boolean removeMember(@PathVariable @NotBlank String teamUuid,
                                @PathVariable @NotNull @Min(1) Long userId) {
        teamService.removeMember(teamUuid, userId);
        return true;
    }

    /**
     * Leave a team; the owner must transfer ownership first
     * 退出团队（OWNER 须先转让所有权）
     */
    @PostMapping("/leave/{uuid}")
    public boolean leave(@PathVariable @NotBlank String uuid) {
        teamService.leave(uuid);
        return true;
    }
}
