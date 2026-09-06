package com.pppp.zhimesh.common.workflow.node.documentextractor;

import com.pppp.zhimesh.common.entity.ZhiMeshFile;
import com.pppp.zhimesh.common.entity.WorkflowComponent;
import com.pppp.zhimesh.common.entity.WorkflowNode;
import com.pppp.zhimesh.common.enums.WfIODataTypeEnum;
import com.pppp.zhimesh.common.file.FileOperatorContext;
import com.pppp.zhimesh.common.service.FileService;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.workflow.NodeProcessResult;
import com.pppp.zhimesh.common.workflow.WfNodeState;
import com.pppp.zhimesh.common.workflow.WfState;
import com.pppp.zhimesh.common.workflow.data.NodeIOData;
import com.pppp.zhimesh.common.workflow.data.NodeIODataFilesContent;
import com.pppp.zhimesh.common.workflow.data.NodeIODataTextContent;
import com.pppp.zhimesh.common.workflow.node.AbstractWfNode;
import com.pppp.zhimesh.common.workflow.metrics.DocumentMetrics;
import dev.langchain4j.data.document.Document;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.DEFAULT_OUTPUT_PARAM_NAME;

/**
 * 【节点】文档解析 <br/>
 */
@Slf4j
public class DocumentExtractorNode extends AbstractWfNode {

    public DocumentExtractorNode(WorkflowComponent wfComponent, WorkflowNode nodeDef, WfState wfState, WfNodeState nodeState) {
        super(wfComponent, nodeDef, wfState, nodeState);
        state.setMetrics(new DocumentMetrics());
    }

    @Override
    public NodeProcessResult onProcess() {
        StringBuilder documentText = new StringBuilder();
        List<NodeIOData> list = state.getInputs();
        List<String> fileUuids = new ArrayList<>();
        for (NodeIOData nodeIOData : list) {
            if (WfIODataTypeEnum.FILES.getValue().equals(nodeIOData.getContent().getType())) {
                NodeIODataFilesContent filesContent = (NodeIODataFilesContent) nodeIOData.getContent();
                fileUuids.addAll(filesContent.getValue());
            }
        }
        FileService fileService = SpringUtil.getBean(FileService.class);
        //解析文档
        try {
            for (String uuid : fileUuids) {
                ZhiMeshFile adiFile = fileService.getFile(uuid);
                Document document = FileOperatorContext.loadDocument(adiFile);
                if (null == document) {
                    log.warn("File type {}:{} cannot be parsed, ignored", adiFile.getUuid(), adiFile.getExt());
                    continue;
                }
                documentText.append(document.text());
            }
        } catch (Exception e) {
            log.error("Failed to parse document", e);
        }
        //记录文档提取指标 | Record document extraction metrics
        DocumentMetrics docMetrics = (DocumentMetrics) state.getMetrics();
        docMetrics.setFileCount(fileUuids.size());
        docMetrics.setExtractedCharCount(documentText.length());
        NodeIODataTextContent dataContent = new NodeIODataTextContent();
        dataContent.setValue(documentText.toString());
        dataContent.setTitle("");
        List<NodeIOData> result = List.of(NodeIOData.builder().name(DEFAULT_OUTPUT_PARAM_NAME).content(dataContent).build());
        return NodeProcessResult.builder().content(result).build();
    }
}
