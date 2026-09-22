package com.pppp.zhimesh.common.util;

import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.text.MessageFormat;
import java.time.Duration;
import java.util.List;

import static com.pppp.zhimesh.common.cosntant.RedisKeyConstant.TOKEN_USAGE_KEY;

@Slf4j
public class LLMTokenUtil {

    /**
     * 缓存token使用情况
     *
     * @param stringRedisTemplate stringRedisTemplate
     * @param uuid                唯一标识
     * @param tokenUsage          token使用量
     */
    public static void cacheTokenUsage(StringRedisTemplate stringRedisTemplate, String uuid, TokenUsage tokenUsage) {
        if (stringRedisTemplate == null || tokenUsage == null || StringUtils.isBlank(uuid)) {
            log.warn("cacheTokenUsage skipped due to invalid arguments, uuid:{}", uuid);
            return;
        }
        String redisKey = MessageFormat.format(TOKEN_USAGE_KEY, uuid);
        Integer inputTokenCount = tokenUsage.inputTokenCount();
        Integer outputTokenCount = tokenUsage.outputTokenCount();
        // Write first, then refresh TTL. EXPIRE on a not-yet-existing key is a no-op,
        // so this order guarantees the key is always created with an expiration.
        stringRedisTemplate.opsForList().rightPushAll(redisKey,
                String.valueOf(inputTokenCount != null ? inputTokenCount : 0),
                String.valueOf(outputTokenCount != null ? outputTokenCount : 0));
        stringRedisTemplate.expire(redisKey, Duration.ofMinutes(10));
    }

    /**
     * 计算缓存在redis中的token使用情况
     *
     * @param stringRedisTemplate stringRedisTemplate
     * @param uuid                唯一标识
     * @return Pair<Integer, Integer> Pair<输入token数量, 输出token数量>
     */
    public static Pair<Integer, Integer> calAllTokenCostByUuid(StringRedisTemplate stringRedisTemplate, String uuid) {
        List<String> tokenCountList = stringRedisTemplate.opsForList().range(MessageFormat.format(TOKEN_USAGE_KEY, uuid), 0, -1);
        int inputTokenCount = 0;
        int outputTokenCount = 0;
        if (!CollectionUtils.isEmpty(tokenCountList) && tokenCountList.size() > 1) {
            int tokenCountListSize = tokenCountList.size();
            int i = 0;
            while (i < tokenCountListSize) {
                inputTokenCount += Integer.parseInt(tokenCountList.get(i));
                i++;
                if (i < tokenCountListSize) {
                    outputTokenCount += Integer.parseInt(tokenCountList.get(i));
                }
                i++;
            }
        }
        return Pair.of(inputTokenCount, outputTokenCount);
    }

    /**
     * 清空某次请求累计的 token 使用记录。重新生成等场景会复用同一 questionUuid，
     * 若不先清空，上一轮尝试的中间轮会与本轮累加，导致 {@link #calAllTokenCostByUuid}
     * 读回后按旧+新双重计费；计费读取失败不能影响回答链路，异常一律吞掉并告警。
     * <p>
     * Clears the accumulated token records of one request. Regenerate reuses the
     * same questionUuid; without clearing, the previous attempt's intermediate
     * rounds accumulate with the new one and {@link #calAllTokenCostByUuid}
     * double-bills both on read-back. A billing-reset failure must never break
     * the answer pipeline, so exceptions are swallowed with a warning.
     */
    public static void resetTokenUsage(StringRedisTemplate stringRedisTemplate, String uuid) {
        if (stringRedisTemplate == null || StringUtils.isBlank(uuid)) {
            return;
        }
        try {
            stringRedisTemplate.delete(MessageFormat.format(TOKEN_USAGE_KEY, uuid));
        } catch (Exception e) {
            log.warn("resetTokenUsage failed, uuid:{}", uuid, e);
        }
    }
}
