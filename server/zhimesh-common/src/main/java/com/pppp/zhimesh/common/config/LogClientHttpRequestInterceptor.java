package com.pppp.zhimesh.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StopWatch;

import java.io.IOException;

@Slf4j
public class LogClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        ClientHttpResponse response = execution.execute(request, body);

        stopWatch.stop();
        // Never consume or log request/response bodies here: they can contain prompts,
        // model answers, credentials or uploaded content. Reading the response stream
        // would also make it unavailable to the actual caller.
        log.info("HTTP client completed, method:{},path:{},status:{},durationMs:{},requestBytes:{}",
                request.getMethod(), request.getURI().getPath(), response.getStatusCode().value(),
                stopWatch.getTotalTimeMillis(), body == null ? 0 : body.length);
        return response;
    }


}
