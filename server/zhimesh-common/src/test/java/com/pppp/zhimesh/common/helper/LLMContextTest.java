package com.pppp.zhimesh.common.helper;

import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.languagemodel.AbstractLLMService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LLMContextTest {

    @AfterEach
    void clearServices() {
        LLMContext.LLM_SERVICES = java.util.List.of();
    }

    @Test
    void returnsExplicitlySelectedModelWithoutHealthGate() {
        AiModel model = new AiModel();
        model.setId(24L);
        model.setName("gpt-5.6-terra");
        AbstractLLMService service = mock(AbstractLLMService.class);
        when(service.getAiModel()).thenReturn(model);
        LLMContext.addLLMService(service);

        assertThat(LLMContext.getServiceById(24L, false)).isSameAs(service);
    }

    @Test
    void replacesAPlatformWithOneAtomicImmutableSnapshot() {
        AbstractLLMService oldService = service(31L, "old-model");
        AbstractLLMService newService = service(32L, "new-model");
        LLMContext.replaceByPlatform("OpenRouter", "text", List.of(oldService));
        List<AbstractLLMService> before = LLMContext.getAllServices();

        LLMContext.replaceByPlatform("OpenRouter", "text", List.of(newService));

        assertThat(before).containsExactly(oldService);
        assertThat(LLMContext.getAllServices()).containsExactly(newService);
    }

    private AbstractLLMService service(long id, String name) {
        AiModel model = new AiModel();
        model.setId(id);
        model.setName(name);
        model.setPlatform("OpenRouter");
        model.setType("text");
        AbstractLLMService service = mock(AbstractLLMService.class);
        when(service.getAiModel()).thenReturn(model);
        return service;
    }
}
