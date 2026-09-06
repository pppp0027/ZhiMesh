package com.pppp.zhimesh.common.workflow.data;

import com.pppp.zhimesh.common.enums.WfIODataTypeEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

@EqualsAndHashCode(callSuper = true)
@Data
public class NodeIODataNumberContent extends NodeIODataContent<Double> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String title;

    private Integer type = WfIODataTypeEnum.NUMBER.getValue();

    private Double value;
}
