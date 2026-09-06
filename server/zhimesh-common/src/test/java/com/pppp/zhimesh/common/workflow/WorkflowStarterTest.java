package com.pppp.zhimesh.common.workflow;

import com.pppp.zhimesh.common.base.ThreadContext;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.exception.BaseException;
import com.pppp.zhimesh.common.util.SpringUtil;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowStarterTest {

    @Test
    void missingInterruptedFlowIsReportedToTheHttpCallerSynchronously() {
        WorkflowStarter starter = new WorkflowStarter();
        ApplicationContext previousContext = (ApplicationContext) ReflectionTestUtils.getField(
                SpringUtil.class, "applicationContext");
        ApplicationContext context = mock(ApplicationContext.class);
        MessageSource messageSource = mock(MessageSource.class);
        when(context.getBean(MessageSource.class)).thenReturn(messageSource);
        when(messageSource.getMessage(anyString(), isNull(), any(Locale.class)))
                .thenReturn("Workflow resume failed");
        ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", context);
        User user = new User();
        user.setLocale("zh-CN");
        ThreadContext.setCurrentUser(user);

        try {
            assertThrows(BaseException.class,
                    () -> starter.resumeFlow("missing-runtime", "continue"));
        } finally {
            ThreadContext.unload();
            ReflectionTestUtils.setField(SpringUtil.class, "applicationContext", previousContext);
        }
    }
}
