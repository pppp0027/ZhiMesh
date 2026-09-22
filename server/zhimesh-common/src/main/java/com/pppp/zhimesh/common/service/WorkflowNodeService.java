package com.pppp.zhimesh.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.ChainWrappers;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.base.NodeInputConfigTypeHandler;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.dto.workflow.WfNodeDto;
import com.pppp.zhimesh.common.entity.Workflow;
import com.pppp.zhimesh.common.entity.WorkflowComponent;
import com.pppp.zhimesh.common.entity.WorkflowNode;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.enums.WfIODataTypeEnum;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.mapper.WorkflowNodeMapper;
import com.pppp.zhimesh.common.util.AesUtil;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.util.MPPageUtil;
import com.pppp.zhimesh.common.util.UuidUtil;
import com.pppp.zhimesh.common.workflow.WfComponentNameEnum;
import com.pppp.zhimesh.common.workflow.WfNodeInputConfig;
import com.pppp.zhimesh.common.workflow.def.WfNodeIOText;
import com.pppp.zhimesh.common.workflow.def.WfNodeIO;
import com.pppp.zhimesh.common.workflow.def.WfNodeIOOptions;
import com.pppp.zhimesh.common.workflow.node.mailsender.MailSendNodeConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;

@Slf4j
@Service
public class WorkflowNodeService extends ServiceImpl<WorkflowNodeMapper, WorkflowNode> {

    @Lazy
    @Resource
    private WorkflowNodeService self;

    @Resource
    private WorkflowComponentService workflowComponentService;

    public WorkflowNode getStartNode(long workflowId) {
        return baseMapper.getStartNode(workflowId);
    }

    /**
     * 批量解析一组工作流起始节点的输入参数定义（一次 in 查询），供 run_workflow 内置
     * 工具把文本 input 包装成起始节点期望的参数名；无起始节点的工作流不在返回 Map 中，
     * 起始组件不可用时返回空 Map（工具侧按"无文本输入"处理，不阻断聊天）
     * <p>
     * Batch-resolve the start nodes' input parameter definitions of the given
     * workflows (one in-query) so the run_workflow builtin tool can wrap the
     * text input under the parameter name the start node expects; workflows
     * without a start node are absent from the returned map, and an unavailable
     * start component yields an empty map (the tool treats it as "no text
     * input" instead of failing the chat).
     */
    public Map<Long, WfNodeInputConfig> getStartNodeInputConfigs(Collection<Long> workflowIds) {
        Map<Long, WfNodeInputConfig> result = new HashMap<>();
        if (CollectionUtils.isEmpty(workflowIds)) {
            return result;
        }
        WorkflowComponent startComponent;
        try {
            startComponent = workflowComponentService.getStartComponent();
        } catch (Exception e) {
            log.warn("Start component unavailable, skip start node input resolution", e);
            return result;
        }
        if (null == startComponent || null == startComponent.getId()) {
            return result;
        }
        ChainWrappers.lambdaQueryChain(baseMapper)
                .in(WorkflowNode::getWorkflowId, workflowIds)
                .eq(WorkflowNode::getWorkflowComponentId, startComponent.getId())
                .list()
                .forEach(node -> result.put(node.getWorkflowId(),
                        null != node.getInputConfig() ? node.getInputConfig() : new WfNodeInputConfig()));
        return result;
    }

    public List<WfNodeDto> listDtoByWfId(long workflowId) {
        List<WorkflowNode> workflowNodeList = ChainWrappers.lambdaQueryChain(baseMapper)
                .eq(WorkflowNode::getWorkflowId, workflowId)
                
                .list();
        workflowNodeList.forEach(this::checkAndDecrypt);
        return MPPageUtil.convertToList(workflowNodeList, WfNodeDto.class, (source, target) -> {
            target.setInputConfig((ObjectNode) JsonUtil.classToJsonNode(source.getInputConfig()));
            return target;
        });
    }

    public WorkflowNode getByUuid(long workflowId, String uuid) {
        WorkflowNode node = ChainWrappers.lambdaQueryChain(baseMapper)
                .eq(WorkflowNode::getWorkflowId, workflowId)
                .eq(WorkflowNode::getUuid, uuid)
                
                .last("limit 1")
                .one();
        checkAndDecrypt(node);
        return node;
    }

    public List<WorkflowNode> listByWorkflowId(Long workflowId) {
        List<WorkflowNode> list = ChainWrappers.lambdaQueryChain(baseMapper)
                .eq(WorkflowNode::getWorkflowId, workflowId)
                
                .list();
        list.forEach(this::checkAndDecrypt);
        return list;
    }

    public List<WorkflowNode> copyByWorkflowId(long workflowId, long targetWorkflowId) {
        List<WorkflowNode> result = new ArrayList<>();
        self.listByWorkflowId(workflowId).forEach(node -> {
            result.add(self.copyNode(targetWorkflowId, node));
        });
        return result;
    }

    public WorkflowNode copyNode(Long targetWorkflowId, WorkflowNode sourceNode) {
        WorkflowNode newNode = new WorkflowNode();
        BeanUtils.copyProperties(sourceNode, newNode, "id", "createTime", "updateTime");
        newNode.setWorkflowId(targetWorkflowId);
        baseMapper.insert(newNode);

        return ChainWrappers.lambdaQueryChain(baseMapper)
                .eq(WorkflowNode::getWorkflowId, targetWorkflowId)
                .eq(WorkflowNode::getUuid, newNode.getUuid())
                
                .last("limit 1")
                .one();
    }

    @Transactional
    public void createOrUpdateNodes(Long workflowId, List<WfNodeDto> nodes) {
        for (WfNodeDto node : nodes) {
            WorkflowNode newOrUpdate = new WorkflowNode();
            BeanUtils.copyProperties(node, newOrUpdate, "inputConfig");
            WfNodeInputConfig inputConfig = NodeInputConfigTypeHandler.createNodeInputConfig(node.getInputConfig());
            validateOptionDefinitions(inputConfig);
            newOrUpdate.setInputConfig(inputConfig);
            newOrUpdate.setWorkflowId(workflowId);
            checkAndEncrypt(newOrUpdate);
            WorkflowNode old = self.getByUuid(workflowId, node.getUuid());
            if (null != old) {
                if (!old.getWorkflowId().equals(node.getWorkflowId())) {
                    log.error("Node does not belong to specified workflow, save failed, workflowId:{}, old workflowId:{}, new workflowId:{}, node uuid:{}, title:{}",
                            workflowId, old.getWorkflowId(), node.getWorkflowId(), node.getUuid(), node.getTitle());
                    throw new BaseException(ErrorEnum.A_PARAMS_ERROR);
                }
                log.info("Updating node, uuid:{}, title:{}", node.getUuid(), node.getTitle());
            } else {
                log.info("Adding node, uuid:{}, title:{}", node.getUuid(), node.getTitle());
                newOrUpdate.setId(null);
            }
            self.saveOrUpdate(newOrUpdate);
        }
    }

    private void checkAndEncrypt(WorkflowNode workflowNode) {
        WorkflowComponent component = workflowComponentService.getAllEnable()
                .stream()
                .filter(item -> item.getId().equals(workflowNode.getWorkflowComponentId()))
                .findFirst()
                .orElse(null);
        if (null == component) {
            log.error("Node not found, uuid:{}, title:{}", workflowNode.getUuid(), workflowNode.getTitle());
            throw new BaseException(ErrorEnum.A_PARAMS_ERROR);
        }
        if (component.getName().equals(WfComponentNameEnum.MAIL_SEND.getName())) {

//Encryption (currently only at database layer, frontend-backend encryption TBD)
            //加密（目前暂时只在数据库层做加密，前后端交互时数据加解密待定）
            MailSendNodeConfig mailSendNodeConfig = JsonUtil.fromJson(workflowNode.getNodeConfig(), MailSendNodeConfig.class);
            if (null != mailSendNodeConfig && null != mailSendNodeConfig.getSender() && null != mailSendNodeConfig.getSender().getPassword()) {
                String password = mailSendNodeConfig.getSender().getPassword();
                // The editor normally receives a decrypted password. When it submits an already
                // encrypted value (for example from a stale client state), avoid encrypting it a
                // second time, which would make SMTP authentication impossible at runtime.
                if (StringUtils.isNotBlank(password) && !isEncryptedMailPassword(password)) {
                    mailSendNodeConfig.getSender().setPassword(AesUtil.encrypt(password));
                    workflowNode.setNodeConfig((ObjectNode) JsonUtil.classToJsonNode(mailSendNodeConfig));
                }
            }
        }
    }

    private void validateOptionDefinitions(WfNodeInputConfig inputConfig) {
        if (inputConfig == null || CollectionUtils.isEmpty(inputConfig.getUserInputs())) {
            return;
        }
        for (WfNodeIO input : inputConfig.getUserInputs()) {
            if (!(input instanceof WfNodeIOOptions optionsInput)) {
                continue;
            }
            List<String> options = optionsInput.getOptions();
            if (CollectionUtils.isEmpty(options)
                    || options.stream().anyMatch(StringUtils::isBlank)
                    || new HashSet<>(options).size() != options.size()) {
                throw new BaseException(ErrorEnum.A_WF_NODE_CONFIG_ERROR);
            }
        }
    }

    private boolean isEncryptedMailPassword(String password) {
        try {
            AesUtil.decrypt(password);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void checkAndDecrypt(WorkflowNode workflowNode) {
        if (null == workflowNode) {
            log.warn("Node does not exist");
            return;
        }
        WorkflowComponent component = workflowComponentService.getAllEnable()
                .stream()
                .filter(item -> item.getId().equals(workflowNode.getWorkflowComponentId()))
                .findFirst()
                .orElse(null);
        if (null == component) {
            log.error("Node not found, uuid:{}, title:{}", workflowNode.getUuid(), workflowNode.getTitle());
            throw new BaseException(ErrorEnum.A_PARAMS_ERROR);
        }
        if (component.getName().equals(WfComponentNameEnum.MAIL_SEND.getName())) {
            MailSendNodeConfig mailSendNodeConfig = JsonUtil.fromJson(workflowNode.getNodeConfig(), MailSendNodeConfig.class);
            if (null != mailSendNodeConfig && null != mailSendNodeConfig.getSender() && null != mailSendNodeConfig.getSender().getPassword()) {
                String password = mailSendNodeConfig.getSender().getPassword();
                if (StringUtils.isNotBlank(password)) {
                    String decrypt = AesUtil.decrypt(password);
                    mailSendNodeConfig.getSender().setPassword(decrypt);
                }
                workflowNode.setNodeConfig((ObjectNode) JsonUtil.classToJsonNode(mailSendNodeConfig));
            }
        }
    }

    @Transactional
    public void deleteNodes(Long workflowId, List<String> uuids) {
        if (CollectionUtils.isEmpty(uuids)) {
            return;
        }
        for (String uuid : uuids) {
            WorkflowNode old = self.getByUuid(workflowId, uuid);
            if (null == old) {
                continue;
            }
            if (!old.getWorkflowId().equals(workflowId)) {
                log.error("Node does not belong to specified workflow, delete failed, workflowId:{}, node workflowId:{}", workflowId, workflowId);
                throw new BaseException(ErrorEnum.A_PARAMS_ERROR);
            }
            if (workflowComponentService.getStartComponent().getId().equals(old.getWorkflowComponentId())) {
                log.warn("Start node cannot be deleted, uuid:{}", old.getUuid());
                continue;
            }
            baseMapper.delete(new LambdaQueryWrapper<WorkflowNode>()
                    .eq(WorkflowNode::getWorkflowId, workflowId)
                    .eq(WorkflowNode::getUuid, uuid));
        }

    }

    /**
     * user_inputs:
     * [
     * {
     * "uuid": "12bc919774aa4e779d97e3dd9c836e11",
     * "name": "var_user_input",
     * "title": "用户输入",
     * "type": 1,
     * "required": true,
     * "max_length": 1000
     * }
     * ]
     *
     * @param workflow 工作流定义
     */
    public WorkflowNode createStartNode(Workflow workflow) {
        String locale = ThreadContext.getCurrentUser() != null ? ThreadContext.getCurrentUser().getLocale() : "";
        String startNodeTitle = (locale != null && locale.startsWith("zh")) ? "开始" : "Start";
        String userInputTitle = (locale != null && locale.startsWith("zh")) ? "用户输入" : "User Input";
        WfNodeIOText wfNodeIOText = WfNodeIOText.builder()
                .uuid(UuidUtil.createShort())
                .type(WfIODataTypeEnum.TEXT.getValue())
                .name("var_user_input")
                .title(userInputTitle)
                .required(false)
                .maxLength(1000)
                .build();
        WfNodeInputConfig nodeInputConfig = new WfNodeInputConfig();
        nodeInputConfig.setUserInputs(List.of(wfNodeIOText));
        nodeInputConfig.setRefInputs(new ArrayList<>());
        WorkflowComponent startComponent = workflowComponentService.getStartComponent();
        WorkflowNode node = new WorkflowNode();
        node.setWorkflowComponentId(startComponent.getId());
        node.setWorkflowId(workflow.getId());
        node.setRemark(userInputTitle);
        node.setUuid(UuidUtil.createShort());
        node.setTitle(startNodeTitle);
        node.setInputConfig(nodeInputConfig);
        baseMapper.insert(node);
        return node;
    }
}
