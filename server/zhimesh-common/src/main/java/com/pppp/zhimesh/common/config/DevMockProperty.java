package com.pppp.zhimesh.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties("zhimesh.dev-mock")
@Data
public class DevMockProperty {
}
