package com.pppp.zhimesh.common.dto;

import com.pppp.zhimesh.common.vo.*;
import lombok.Data;

@Data
public class StatisticDto {
    private UserStatistic userStatistic;
    private KbStatistic kbStatistic;
    private TokenCostStatistic tokenCostStatistic;
    private CharacterStatistic characterStatistic;
    private ImageCostStatistic imageCostStatistic;
}
