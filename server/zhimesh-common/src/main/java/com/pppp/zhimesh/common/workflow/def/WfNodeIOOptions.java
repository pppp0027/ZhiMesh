package com.pppp.zhimesh.common.workflow.def;

import com.pppp.zhimesh.common.enums.WfIODataTypeEnum;
import com.pppp.zhimesh.common.workflow.data.NodeIOData;
import com.pppp.zhimesh.common.workflow.data.NodeIODataOptionsContent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Map;
import java.util.List;

/**
 * 用户输入参数-下拉选项类型 参数定义
 */
@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public class WfNodeIOOptions extends WfNodeIO {
    protected Integer type = WfIODataTypeEnum.OPTIONS.getValue();
    private Boolean multiple;
    private List<String> options;

    @Override
    public boolean checkValue(NodeIOData data) {
        if (!(data.getContent() instanceof NodeIODataOptionsContent optionsData)) {
            return false;
        }
        Map<String, Object> value = optionsData.getValue();
        if (Boolean.TRUE.equals(required) && (null == value || value.isEmpty())) {
            return false;
        }
//If single selection is set but multiple values are passed, validation fails
        //如果设置了单选，传过来的值是多项，则检查不通过
        if (!Boolean.TRUE.equals(multiple) && null != value && value.size() > 1) {
            return false;
        }
        // Legacy definitions may not contain an options list. New definitions constrain submitted
        // keys to the configured values so the backend never trusts a forged client selection.
        return null == value || null == options || options.isEmpty() || options.containsAll(value.keySet());
    }
}
