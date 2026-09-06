package com.pppp.zhimesh.common.workflow.def;

import com.pppp.zhimesh.common.enums.WfIODataTypeEnum;
import com.pppp.zhimesh.common.workflow.data.NodeIOData;
import com.pppp.zhimesh.common.workflow.data.NodeIODataNumberContent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 用户输入参数-数字类型 参数定义
 */
@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public class WfNodeIONumber extends WfNodeIO {
    protected Integer type = WfIODataTypeEnum.NUMBER.getValue();

    @Override
    public boolean checkValue(NodeIOData data) {
        if (!(data.getContent() instanceof NodeIODataNumberContent nodeIONumber)) {
            return false;
        }
        return !required || null != nodeIONumber.getValue();
    }
}
