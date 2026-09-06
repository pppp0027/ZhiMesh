package com.pppp.zhimesh.admin.controller;

import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.OpenRouterModelState;
import com.pppp.zhimesh.common.entity.OpenRouterSyncRun;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.openrouter.OpenRouterModelSyncService;
import com.pppp.zhimesh.common.service.OpenRouterModelStateService;
import com.pppp.zhimesh.common.service.OpenRouterSyncRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_DATA_NOT_FOUND;
import static com.pppp.zhimesh.common.enums.ErrorEnum.A_USER_NOT_AUTH;

@Tag(name = "OpenRouter 模型同步 | OpenRouter Model Sync")
@RestController
@RequestMapping("/admin/openrouter-model-sync")
public class AdminOpenRouterModelSyncController {

    private final OpenRouterModelSyncService syncService;
    private final OpenRouterSyncRunService runService;
    private final OpenRouterModelStateService stateService;
    private final ZhiMeshProperties properties;

    public AdminOpenRouterModelSyncController(OpenRouterModelSyncService syncService,
                                              OpenRouterSyncRunService runService,
                                              OpenRouterModelStateService stateService,
                                              ZhiMeshProperties properties) {
        this.syncService = syncService;
        this.runService = runService;
        this.stateService = stateService;
        this.properties = properties;
    }

    @Operation(summary = "立即同步 OpenRouter 免费模型 | Run OpenRouter Model Sync")
    @PostMapping("/run")
    public OpenRouterSyncRun run() {
        requireAdmin();
        return syncService.queueManual();
    }

    @Operation(summary = "查询同步任务 | Get Sync Run")
    @GetMapping("/runs/{runId}")
    public OpenRouterSyncRun getRun(@PathVariable String runId) {
        requireAdmin();
        OpenRouterSyncRun run = runService.getByUuid(runId);
        if (run == null) {
            throw new BaseException(A_DATA_NOT_FOUND);
        }
        return run;
    }

    @Operation(summary = "查询最近一次同步 | Get Latest Sync Run")
    @GetMapping("/latest")
    public OpenRouterSyncRun latest() {
        requireAdmin();
        return runService.latest();
    }

    @Operation(summary = "查询 OpenRouter 模型同步状态 | List OpenRouter Model States")
    @GetMapping("/models")
    public List<OpenRouterModelState> models() {
        requireAdmin();
        return stateService.listByPlatform(properties.getOpenrouterSync().getPlatformName());
    }

    private void requireAdmin() {
        if (!Boolean.TRUE.equals(ThreadContext.getExistCurrentUser().getIsAdmin())) {
            throw new BaseException(A_USER_NOT_AUTH);
        }
    }
}
