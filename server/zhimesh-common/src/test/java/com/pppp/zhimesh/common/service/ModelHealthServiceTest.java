package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.enums.ModelHealthStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelHealthServiceTest {

    @Test
    void toleratesShortFailureBurstAndRecoversImmediatelyAfterSuccess() {
        ModelHealthService service = new ModelHealthService();
        String platform = "OpenRouter";
        String modelName = "temporary-network-test-model";

        for (int i = 1; i < ModelHealthService.FAILURE_THRESHOLD; i++) {
            service.recordFailure(platform, modelName, "temporary timeout");
            assertThat(service.getStatus(platform, modelName)).isEqualTo(ModelHealthStatus.HEALTHY);
        }

        service.recordFailure(platform, modelName, "temporary timeout");
        assertThat(service.getStatus(platform, modelName)).isEqualTo(ModelHealthStatus.UNHEALTHY);

        service.recordSuccess(platform, modelName);
        assertThat(service.getStatus(platform, modelName)).isEqualTo(ModelHealthStatus.HEALTHY);
    }

    @Test
    void isolatesSameModelNameAcrossPlatforms() {
        ModelHealthService service = new ModelHealthService();
        String modelName = "shared-model-name";

        for (int i = 0; i < ModelHealthService.FAILURE_THRESHOLD; i++) {
            service.recordFailure("OpenRouter", modelName, "provider unavailable");
        }

        assertThat(service.getStatus("OpenRouter", modelName)).isEqualTo(ModelHealthStatus.UNHEALTHY);
        assertThat(service.getStatus("MiddleStation", modelName)).isEqualTo(ModelHealthStatus.HEALTHY);
    }
}
