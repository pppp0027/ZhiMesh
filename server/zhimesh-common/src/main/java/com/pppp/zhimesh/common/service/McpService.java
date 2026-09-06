package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.ChainWrappers;
import com.pppp.zhimesh.common.dto.mcp.McpAddOrEditReq;
import com.pppp.zhimesh.common.dto.mcp.McpCommonParam;
import com.pppp.zhimesh.common.dto.mcp.McpSearchReq;
import com.pppp.zhimesh.common.entity.Mcp;
import com.pppp.zhimesh.common.entity.UserMcp;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.mapper.McpMapper;
import com.pppp.zhimesh.common.mapper.UserMcpMapper;
import com.pppp.zhimesh.common.util.AesUtil;
import com.pppp.zhimesh.common.util.PrivilegeUtil;
import com.pppp.zhimesh.common.util.UuidUtil;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.pppp.zhimesh.common.enums.ErrorEnum.A_MCP_SERVER_NOT_FOUND;

/**
 * Mcp服务类，MCP信息只能由系统管理员进行维护
 */
@Service
public class McpService extends ServiceImpl<McpMapper, Mcp> {

    @Resource
    private UserMcpMapper userMcpMapper;

    public Mcp addOrUpdate(McpAddOrEditReq addOrEditReq, boolean decryptEnv) {
        Mcp result;
        if (StringUtils.isBlank(addOrEditReq.getUuid())) {
            Mcp mcp = new Mcp();
            BeanUtils.copyProperties(addOrEditReq, mcp);
            mcp.setUuid(UuidUtil.createShort());
            encryptEnv(mcp.getPresetParams());
            this.save(mcp);
            result = mcp;
        } else {
            Mcp mcp = PrivilegeUtil.checkAndGetByUuid(addOrEditReq.getUuid(), this.query(), A_MCP_SERVER_NOT_FOUND);
            Mcp updateObj = new Mcp();
            BeanUtils.copyProperties(addOrEditReq, updateObj, "id", "uuid");
            encryptEnv(updateObj.getPresetParams());
            updateObj.setId(mcp.getId());
            this.updateById(updateObj);
            result = updateObj;
        }
        return decryptEnv(result, decryptEnv);
    }

    public Page<Mcp> search(McpSearchReq req, Integer currentPage, Integer pageSize, boolean decryptEnv) {
        Page<Mcp> page = this.lambdaQuery()
                .like(StringUtils.isNotBlank(req.getTitle()), Mcp::getTitle, req.getTitle())
                .eq(StringUtils.isNotBlank(req.getInstallType()), Mcp::getInstallType, req.getInstallType())
                .eq(StringUtils.isNotBlank(req.getTransportType()), Mcp::getTransportType, req.getTransportType())
                .eq(null != req.getIsEnable(), Mcp::getIsEnable, req.getIsEnable())
                
                .orderByDesc(Mcp::getUpdateTime)
                .page(new Page<>(currentPage, pageSize));
        for (Mcp mcp : page.getRecords()) {
            decryptEnv(mcp, decryptEnv);
        }
        return page;
    }

    public List<Mcp> listByIds(List<Long> ids, boolean decryptEnv) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Mcp> list = this.lambdaQuery()
                .in(Mcp::getId, ids)
                
                .list();
        for (Mcp mcp : list) {
            decryptEnv(mcp, decryptEnv);
        }
        return list;
    }

    /** Returns only MCP definitions that are currently available at runtime. */
    public List<Mcp> listEnabledByIds(List<Long> ids, boolean decryptEnv) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Mcp> list = this.lambdaQuery()
                .in(Mcp::getId, ids)
                .eq(Mcp::getIsEnable, true)
                
                .list();
        for (Mcp mcp : list) {
            decryptEnv(mcp, decryptEnv);
        }
        return list;
    }

    /**
     * 获取系统中所有已启用的 MCP 服务列表
     */
    public List<Mcp> listAllEnabled() {
        return this.lambdaQuery()
                .eq(Mcp::getIsEnable, true)
                
                .orderByDesc(Mcp::getUpdateTime)
                .list();
    }

    public int enable(String uuid, Boolean isEnable) {
        Mcp existObj = PrivilegeUtil.checkAndGetByUuid(uuid, this.query(), A_MCP_SERVER_NOT_FOUND);
        Mcp updateObj = new Mcp();
        updateObj.setId(existObj.getId());
        updateObj.setIsEnable(isEnable);
        return baseMapper.updateById(updateObj);
    }

    @Transactional(rollbackFor = Exception.class)
    public void softDelete(String uuid) {
        Mcp mcp = PrivilegeUtil.checkAndGetByUuid(uuid, this.query(), A_MCP_SERVER_NOT_FOUND);
        userMcpMapper.delete(new LambdaQueryWrapper<UserMcp>()
                .eq(UserMcp::getMcpId, mcp.getId()));
        baseMapper.deleteById(mcp.getId());
    }

    public Mcp getOrThrow(Long id, boolean decryptEnv) {
        Mcp mcp = ChainWrappers.lambdaQueryChain(baseMapper).eq(Mcp::getId, id)
                
                .oneOpt()
                .orElseThrow(() -> new BaseException(A_MCP_SERVER_NOT_FOUND));
        return decryptEnv(mcp, decryptEnv);
    }

    private void encryptEnv(List<McpCommonParam> env) {
        if (env != null && !env.isEmpty()) {
            for (McpCommonParam e : env) {
                if (Boolean.TRUE.equals(e.getRequireEncrypt()) && e.getValue() != null) {
                    e.setValue(AesUtil.encrypt(String.valueOf(e.getValue())));
                    e.setEncrypted(true);
                }
            }
        }
    }

    /**
     * 解密环境变量，非管理员则将该变量替换为"***"。
     *
     * @param mcp        mcp对象
     * @param decryptEnv 是否需要解密环境变量
     * @return 解密后的mcp对象
     */
    private Mcp decryptEnv(Mcp mcp, boolean decryptEnv) {
        if (mcp.getPresetParams() != null && !mcp.getPresetParams().isEmpty()) {
            for (McpCommonParam e : mcp.getPresetParams()) {
                if (decryptEnv) {
                    //已经加密的内容需要解密
                    if (Boolean.TRUE.equals(e.getEncrypted()) && e.getValue() != null) {
                        e.setValue(AesUtil.decrypt(String.valueOf(e.getValue())));
                    }
                } else {
                    e.setValue("***");
                }
            }
        }
        return mcp;
    }
}
