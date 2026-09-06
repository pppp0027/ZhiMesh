package com.pppp.zhimesh.common.workflow.node.tongyiwanx;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.entity.Draw;
import com.pppp.zhimesh.common.entity.WorkflowComponent;
import com.pppp.zhimesh.common.entity.WorkflowNode;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.helper.ImageModelContext;
import com.pppp.zhimesh.common.languagemodel.AbstractImageModelService;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.workflow.NodeProcessResult;
import com.pppp.zhimesh.common.workflow.WfNodeState;
import com.pppp.zhimesh.common.workflow.WfState;
import com.pppp.zhimesh.common.workflow.WorkflowUtil;
import com.pppp.zhimesh.common.workflow.node.AbstractWfNode;
import com.pppp.zhimesh.common.workflow.node.DrawNodeUtil;
import com.pppp.zhimesh.common.workflow.metrics.ImageMetrics;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.ModelPlatform.DASHSCOPE;
import static com.pppp.zhimesh.common.enums.ErrorEnum.*;

/**
 * 【节点】通义万相-生成图片 <br/>
 */
@Slf4j
public class TongyiwanxNode extends AbstractWfNode {

    public TongyiwanxNode(WorkflowComponent wfComponent, WorkflowNode nodeDef, WfState wfState, WfNodeState nodeState) {
        super(wfComponent, nodeDef, wfState, nodeState);
        state.setMetrics(new ImageMetrics());
    }

    @Override
    public NodeProcessResult onProcess() {
        ObjectNode objectConfig = node.getNodeConfig();
        if (objectConfig.isEmpty()) {
            throw new BaseException(A_WF_NODE_CONFIG_NOT_FOUND);
        }
        TongyiwanxNodeConfig nodeConfigObj = JsonUtil.fromJson(objectConfig, TongyiwanxNodeConfig.class);
        if (null == nodeConfigObj || StringUtils.isBlank(nodeConfigObj.getModelName())) {
            log.warn("Tongyiwanx node configuration missing or incorrect");
            throw new BaseException(A_WF_NODE_CONFIG_ERROR);
        }
        log.info("TongyiwanxNode config:{}", nodeConfigObj);
        String prompt;
        if (StringUtils.isNotBlank(nodeConfigObj.getPrompt())) {
            prompt = WorkflowUtil.renderTemplate(nodeConfigObj.getPrompt(), state.getInputs());
        } else {
            prompt = getFirstInputText();
        }
        log.info("Tongyi Wanx prompt prepared, nodeUuid:{},promptChars:{}", state.getUuid(), prompt.length());
        if (StringUtils.isBlank(prompt)) {
            log.warn("Tongyiwanx node prompt not found");
            throw new BaseException(A_WF_NODE_CONFIG_ERROR);
        }
        AbstractImageModelService imageModelService = ImageModelContext.getOrDefault(nodeConfigObj.getModelName());
        //记录图片生成指标 | Record image generation metrics
        ImageMetrics imageMetrics = (ImageMetrics) state.getMetrics();
        imageMetrics.setImageModelName(nodeConfigObj.getModelName());
        imageMetrics.setImageSize(nodeConfigObj.getSize());
        if (null == imageModelService) {
            log.error("image service not found,ai platform:{}", DASHSCOPE);
            throw new BaseException(A_MODEL_NOT_FOUND);
        }
        Draw draw = new Draw();
        draw.setGenerateNumber(1);
        draw.setPrompt(prompt);
        draw.setGenerateSize(nodeConfigObj.getSize());
        draw.setGenerateSeed(nodeConfigObj.getSeed());
        draw.setAiModelName(nodeConfigObj.getModelName());
        return DrawNodeUtil.createResultContent(wfState.getUser(), draw, imageModelService);
    }
}
