package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.util.SpringUtil;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Locale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 单测专用的 SpringUtil 消息源桩：BaseException 构造时会经 SpringUtil 解析
 * i18n 文案，脱离容器的服务级单测需要先装一个最小 MessageSource。
 * 用法：try (SpringMessageSourceStub ignored = SpringMessageSourceStub.install()) { ... }
 * （照 WorkflowStarterTest 的反射注入手法，退出时恢复原 context。）
 */
final class SpringMessageSourceStub implements AutoCloseable {

    private final ApplicationContext previousContext;

    private SpringMessageSourceStub(ApplicationContext previousContext) {
        this.previousContext = previousContext;
    }

    static SpringMessageSourceStub install() {
        ApplicationContext previousContext = (ApplicationContext) ReflectionTestUtils.getField(
                SpringUtil.class, "applicationContext");
        ApplicationContext context = mock(ApplicationContext.class);
        MessageSource messageSource = mock(MessageSource.class);
        when(context.getBean(MessageSource.class)).thenReturn(messageSource);
        when(messageSource.getMessage(anyString(), isNull(), any(Locale.class)))
                .thenReturn("stub-message");
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", context);
        return new SpringMessageSourceStub(previousContext);
    }

    @Override
    public void close() {
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", previousContext);
    }
}
