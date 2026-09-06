package com.pppp.zhimesh.common.aop;

import com.pppp.zhimesh.common.annotation.ParamsLog;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * @author myz
 */
@Slf4j
@Aspect
@Component
public class ParamsLogAspect {

    @Before(value = "@annotation(paramsLog)")
    public void before(JoinPoint joinPoint, ParamsLog paramsLog) {
        paramsLog(joinPoint, log);
    }

    /**
     * 输出方法参数到日志
     * Output method parameters to log
     *
     * @param joinPoint joinPoint
     * @param logger    日志 / Logger
     */
    static void paramsLog(JoinPoint joinPoint, Logger logger) {
        String className = joinPoint.getSignature().getDeclaringType().getName();
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        logger.info("{}.{} invoked, argumentCount:{}", className, method.getName(), joinPoint.getArgs().length);
    }
}
