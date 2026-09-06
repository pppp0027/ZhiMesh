package com.pppp.zhimesh.common.dto;

import com.pppp.zhimesh.common.vo.TokenCostStatistic;
import lombok.Data;

@Data
public class CostStatResp {
    private TokenCostStatistic freeTokenCost;
    private TokenCostStatistic paidTokenCost;
    private RequestTimesStatistic paidRequestTimes;
    private RequestTimesStatistic freeRequestTimes;
    private DrawTimesStatistic paidDrawTimes;
    private DrawTimesStatistic freeDrawTimes;
}
