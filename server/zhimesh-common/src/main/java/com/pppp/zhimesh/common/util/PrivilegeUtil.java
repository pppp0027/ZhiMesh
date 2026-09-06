package com.pppp.zhimesh.common.util;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.entity.BaseEntity;
import com.pppp.zhimesh.common.enums.ErrorEnum;
import com.pppp.zhimesh.common.exception.BaseException;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.*;

public class PrivilegeUtil {

    private PrivilegeUtil() {
    }

    public static <T> T checkAndGetById(Long id, QueryChainWrapper<T> lambdaQueryChainWrapper, ErrorEnum exceptionMessage) {
        return checkAndGet(id, null, lambdaQueryChainWrapper, exceptionMessage);
    }

    public static <T> T checkAndGetByUuid(String uuid, QueryChainWrapper<T> lambdaQueryChainWrapper, ErrorEnum exceptionMessage) {
        return checkAndGet(null, uuid, lambdaQueryChainWrapper, exceptionMessage);
    }

    public static <T> T checkAndGet(Long id, String uuid, QueryChainWrapper<T> lambdaQueryChainWrapper, ErrorEnum exceptionMessage) {
        T target;
        if (Boolean.TRUE.equals(ThreadContext.getCurrentUser().getIsAdmin())) {
            target = lambdaQueryChainWrapper
                    .eq(null != id, COLUMN_NAME_ID, id)
                    .eq(null != uuid, COLUMN_NAME_UUID, uuid).oneOpt()
                    .orElse(null);
        } else {
            target = lambdaQueryChainWrapper
                    .eq(null != id, COLUMN_NAME_ID, id)
                    .eq(null != uuid, COLUMN_NAME_UUID, uuid)
                    .eq(COLUMN_NAME_USER_ID, ThreadContext.getCurrentUserId())
                    .oneOpt()
                    .orElse(null);
        }
        if (null == target) {
            throw new BaseException(exceptionMessage);
        }
        return target;
    }

}
