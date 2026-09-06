package com.pppp.zhimesh.common.helper;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.vo.CostStat;
import com.pppp.zhimesh.common.service.SysConfigService;
import com.pppp.zhimesh.common.service.UserService;
import com.pppp.zhimesh.common.service.UserDayCostService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class QuotaHelper {

    @Resource
    private UserDayCostService userDayCostService;

    @Resource
    private UserService userService;

    public ErrorEnum checkTextQuota(User user) {
        // The request user comes from the Redis session cache and can be stale
        // after an administrator changes a quota. Always use the latest row.
        User quotaUser = userService.getById(user.getId());
        if (quotaUser == null) {
            quotaUser = user;
        }
        int quotaByTokenDay = resolveQuota(quotaUser.getQuotaByTokenDaily(), ZhiMeshConstant.SysConfigKey.QUOTA_BY_TOKEN_DAILY);
        int quotaByTokenMonth = resolveQuota(quotaUser.getQuotaByTokenMonthly(), ZhiMeshConstant.SysConfigKey.QUOTA_BY_TOKEN_MONTHLY);
        int quotaByRequestDay = resolveQuota(quotaUser.getQuotaByRequestDaily(), ZhiMeshConstant.SysConfigKey.QUOTA_BY_REQUEST_DAILY);
        int quotaByRequestMonth = resolveQuota(quotaUser.getQuotaByRequestMonthly(), ZhiMeshConstant.SysConfigKey.QUOTA_BY_REQUEST_MONTHLY);

        CostStat costStat = userDayCostService.costStatByUser(user.getId(), false);

        boolean dailyTokenExceeded = quotaByTokenDay > 0 && costStat.getTextTokenCostByDay() >= quotaByTokenDay;
        boolean dailyRequestExceeded = quotaByRequestDay > 0 && costStat.getTextRequestTimesByDay() >= quotaByRequestDay;
        if (dailyTokenExceeded || dailyRequestExceeded) {
            log.warn("Reach limit of a day,userId:{},token quota:{},request quota:{},used token:{}, used request:{}", user.getId(), quotaByTokenDay, quotaByRequestDay, costStat.getTextTokenCostByDay(), costStat.getTextRequestTimesByDay());
            return ErrorEnum.B_DAILY_QUOTA_USED;
        }

        boolean monthlyTokenExceeded = quotaByTokenMonth > 0 && costStat.getTextTokenCostByMonth() >= quotaByTokenMonth;
        boolean monthlyRequestExceeded = quotaByRequestMonth > 0 && costStat.getTextRequestTimesByMonth() >= quotaByRequestMonth;
        if (monthlyTokenExceeded || monthlyRequestExceeded) {
            log.warn("Reach limit of a month,userId:{},token quota:{},request quota:{},used token:{}, used request:{}", user.getId(), quotaByTokenMonth, quotaByRequestMonth, costStat.getTextTokenCostByMonth(), costStat.getTextRequestTimesByMonth());
            return ErrorEnum.B_MONTHLY_QUOTA_USED;
        }

        return null;
    }

    public ErrorEnum checkImageQuota(User user, boolean isFree) {
        int dailyQuota = resolveQuota(user.getQuotaByImageDaily(), ZhiMeshConstant.SysConfigKey.QUOTA_BY_IMAGE_DAILY);
        int monthlyQuota = resolveQuota(user.getQuotaByImageMonthly(), ZhiMeshConstant.SysConfigKey.QUOTA_BY_IMAGE_MONTHLY);

        CostStat costStat = userDayCostService.costStatByUser(user.getId(), isFree);

        if (dailyQuota > 0 && costStat.getDrawTimesByDay() >= dailyQuota) {
            log.warn("Generate image reach limit of a day,userId:{},request quota:{},used request times:{}", user.getId(), dailyQuota, costStat.getDrawTimesByDay());
            return ErrorEnum.B_DAILY_QUOTA_USED;
        }
        if (monthlyQuota > 0 && costStat.getDrawTimesByMonth() >= monthlyQuota) {
            log.warn("Generate image reach limit of a month,userId:{},request quota:{},used request times:{}", user.getId(), monthlyQuota, costStat.getDrawTimesByMonth());
            return ErrorEnum.B_MONTHLY_QUOTA_USED;
        }

        return null;
    }

    /**
     * Resolve quota: use user-specific value if set (>0), otherwise fall back to system default.
     */
    private int resolveQuota(int userQuota, String sysConfigKey) {
        if (userQuota > 0) {
            return userQuota;
        }
        return SysConfigService.getIntByKey(sysConfigKey, 0);
    }
}
