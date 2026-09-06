package com.pppp.zhimesh.common.languagemodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.pppp.zhimesh.common.entity.AiModel;
import com.pppp.zhimesh.common.entity.ModelPlatform;
import com.pppp.zhimesh.common.file.LocalFileUtil;
import com.pppp.zhimesh.common.util.JsonUtil;
import com.pppp.zhimesh.common.util.UuidUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Consts;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.File;
import java.io.IOException;

import static com.pppp.zhimesh.common.cosntant.ZhiMeshConstant.FORM_DATA_BOUNDARY_PRE;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;

@Slf4j
public class OpenAiAsrService extends AbstractAsrModelService {

    public OpenAiAsrService(AiModel model, ModelPlatform modelPlatform) {
        super(model, modelPlatform);
    }

    @Override
    public String audioToText(String urlOrPath) {
        String audioPath = urlOrPath;
        if (audioPath.indexOf("http") == 0) {
            audioPath = LocalFileUtil.saveFromUrl(audioPath, UuidUtil.createShort(), "wav");
        }
        log.info("Starting OpenAI ASR transcription");
        String baseUrl = platform.getBaseUrl();
        if (StringUtils.isBlank(baseUrl)) {
            baseUrl = "https://api.openai.com/v1";
        }
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(30 * 1000)
                .setConnectTimeout(30 * 1000)
                .build();
        try (CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .setRetryHandler(new DefaultHttpRequestRetryHandler(0, true))
                .build()) {
            String boundary = FORM_DATA_BOUNDARY_PRE + System.currentTimeMillis();
            HttpPost httpRequest = new HttpPost(baseUrl + "/audio/transcriptions");
            httpRequest.addHeader(CONTENT_TYPE, "multipart/form-data; boundary=" + boundary);
            httpRequest.addHeader(AUTHORIZATION, "Bearer " + platform.getApiKey());
            MultipartEntityBuilder entityBuilder = MultipartEntityBuilder.create();
            entityBuilder.setBoundary(boundary);
            entityBuilder.addPart("file", new FileBody(new File(audioPath)));
            entityBuilder.addTextBody("model", aiModel.getName());
            httpRequest.setEntity(entityBuilder.build());
            try (CloseableHttpResponse response = httpClient.execute(httpRequest)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity(), Consts.UTF_8);
                log.info("OpenAI ASR response received, status:{},responseChars:{}", statusCode, responseBody.length());
                if (statusCode == 200) {
                    JsonNode jsonNode = JsonUtil.toJsonNode(responseBody);
                    if (null == jsonNode) {
                        log.error("OpenAI ASR response is not valid JSON, responseChars:{}", responseBody.length());
                        return null;
                    }
                    if (jsonNode.has("text")) {
                        String text = jsonNode.get("text").asText();
                        log.info("OpenAI ASR transcription completed, textChars:{}", text.length());
                        return text;
                    } else {
                        log.error("OpenAI ASR response does not contain text, responseChars:{}", responseBody.length());
                    }
                } else {
                    log.error("OpenAI ASR request failed, status:{}", statusCode);
                }
            }
        } catch (IOException e) {
            log.error("OpenAI ASR request failed, errorType:{}", e.getClass().getSimpleName());
        }
        return null;
    }
}
