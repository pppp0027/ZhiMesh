package com.pppp.zhimesh.common.workflow.node;

import com.pppp.zhimesh.common.entity.ZhiMeshFile;
import com.pppp.zhimesh.common.entity.Draw;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.file.FileOperatorContext;
import com.pppp.zhimesh.common.languagemodel.AbstractImageModelService;
import com.pppp.zhimesh.common.service.FileService;
import com.pppp.zhimesh.common.util.SpringUtil;
import com.pppp.zhimesh.common.workflow.NodeProcessResult;
import com.pppp.zhimesh.common.workflow.data.NodeIOData;
import com.pppp.zhimesh.common.workflow.data.NodeIODataFilesContent;
import org.apache.commons.collections4.CollectionUtils;

import java.util.List;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.WorkflowConstant.DEFAULT_OUTPUT_PARAM_NAME;

public class DrawNodeUtil {

    /**
     * 绘图并将结果组装以输出到下一节点
     *
     * @param user              执行用户
     * @param draw              绘图信息
     * @param imageModelService 绘图service
     * @return 节点处理结果
     */
    public static NodeProcessResult createResultContent(User user, Draw draw, AbstractImageModelService imageModelService) {
        List<String> images = imageModelService.generateImage(user, draw);
        FileService fileService = SpringUtil.getBean(FileService.class);
        String imageUrl = "";
        if (CollectionUtils.isNotEmpty(images)) {
            ZhiMeshFile adiFile = fileService.saveImageFromUrl(user, images.get(0));
            imageUrl = FileOperatorContext.getFileUrl(adiFile);
        }
        NodeIODataFilesContent datContent = new NodeIODataFilesContent();
        datContent.setValue(List.of(imageUrl));
        datContent.setTitle("");
        List<NodeIOData> result = List.of(NodeIOData.builder().name(DEFAULT_OUTPUT_PARAM_NAME).content(datContent).build());
        return NodeProcessResult.builder().content(result).build();
    }
}
