package com.pppp.zhimesh.common.rag.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.KnowledgeBaseChunk;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import com.pppp.zhimesh.common.mapper.KnowledgeBaseChunkMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Collects a bounded, auditable candidate pool without invoking an LLM. */
@Component
public class KnowledgeRouteProfileCandidateCollector {

    private final KnowledgeBaseChunkMapper chunkMapper;
    private final ObjectMapper objectMapper;
    private final ZhiMeshProperties properties;

    public KnowledgeRouteProfileCandidateCollector(KnowledgeBaseChunkMapper chunkMapper,
                                                   ObjectMapper objectMapper,
                                                   ZhiMeshProperties properties) {
        this.chunkMapper = chunkMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<KnowledgeRouteProfileCandidate> collect(KnowledgeBase kb, List<KnowledgeBaseItem> items) {
        int poolLimit = Math.max(1, properties.getKnowledgeScopeGate().getCandidatePoolLimit());
        int textLimit = Math.max(64, properties.getKnowledgeScopeGate().getProfileTextMaxChars());
        List<KnowledgeRouteProfileCandidate> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        add(result, seen, "OVERVIEW", StringUtils.defaultString(kb.getTitle()) + "\n"
                        + StringUtils.defaultString(kb.getRemark()),
                reference("knowledge_base", kb.getUuid()), textLimit, poolLimit);

        int documentBudget = Math.max(1, poolLimit / 2);
        for (KnowledgeBaseItem item : items) {
            if (result.size() >= documentBudget) break;
            add(result, seen, "DOCUMENT", StringUtils.defaultString(item.getTitle()) + "\n"
                            + StringUtils.defaultString(item.getBrief()),
                    reference("knowledge_base_item", item.getUuid()), textLimit, poolLimit);
        }

        int remaining = Math.max(0, poolLimit - result.size());
        if (remaining > 0) {
            List<KnowledgeBaseChunk> samples = chunkMapper.selectRouteProfileSamples(kb.getUuid(), remaining);
            for (KnowledgeBaseChunk chunk : samples) {
                add(result, seen, "TOPIC", chunk.getContent(),
                        reference("knowledge_base_chunk", chunk.getUuid()), textLimit, poolLimit);
            }
        }
        return List.copyOf(result);
    }

    private void add(List<KnowledgeRouteProfileCandidate> result, Set<String> seen,
                     String type, String rawText, JsonNode reference,
                     int textLimit, int poolLimit) {
        if (result.size() >= poolLimit) return;
        String normalized = normalize(rawText);
        if (normalized.length() < 2) return;
        String text = StringUtils.substring(normalized, 0, textLimit);
        String hash = sha256(text);
        if (!seen.add(hash)) return;
        ArrayNode refs = objectMapper.createArrayNode().add(reference);
        result.add(new KnowledgeRouteProfileCandidate(type,
                type.toLowerCase(Locale.ROOT) + "-" + hash.substring(0, 16), text, refs));
    }

    private ObjectNode reference(String type, String uuid) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", type);
        node.put("uuid", StringUtils.defaultString(uuid));
        return node;
    }

    static String normalize(String value) {
        String normalized = Normalizer.normalize(StringUtils.defaultString(value), Normalizer.Form.NFKC);
        return normalized.replaceAll("\\s+", " ").trim();
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
