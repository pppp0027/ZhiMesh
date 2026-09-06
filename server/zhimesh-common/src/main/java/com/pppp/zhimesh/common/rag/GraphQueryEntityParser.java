package com.pppp.zhimesh.common.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.util.ZhiMeshStringUtil;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses query-time entity extraction output without retaining or logging the
 * model response. JSON is preferred; the legacy delimiter format remains a
 * compatibility fallback while existing model configurations are migrated.
 */
final class GraphQueryEntityParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private GraphQueryEntityParser() {
    }

    static ParseResult parse(String response) {
        if (StringUtils.isBlank(response)) {
            return new ParseResult(Set.of(), "blank", false);
        }
        String json = extractJsonObject(response);
        if (json != null) {
            try {
                Set<String> entities = parseJson(json);
                return new ParseResult(normalize(entities), "json", true);
            } catch (Exception ignored) {
                // Fall through to the legacy parser. The raw response may
                // contain user data and must never be written to logs.
            }
        }
        Set<String> legacyEntities = parseLegacy(response);
        return new ParseResult(normalize(legacyEntities),
                legacyEntities.isEmpty() ? "unparsed" : "legacy", true);
    }

    private static Set<String> parseJson(String json) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(json);
        JsonNode entityNodes = root.path("entities");
        Set<String> entities = new LinkedHashSet<>();
        if (!entityNodes.isArray()) {
            return entities;
        }
        for (JsonNode entityNode : entityNodes) {
            String name;
            if (entityNode.isTextual()) {
                name = entityNode.asText();
            } else {
                name = firstText(entityNode, "name", "entity_name", "entityName");
            }
            if (StringUtils.isNotBlank(name)) {
                entities.add(name);
            }
        }
        return entities;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode() && StringUtils.isNotBlank(value.asText())) {
                return value.asText();
            }
        }
        return null;
    }

    private static Set<String> parseLegacy(String response) {
        Set<String> entities = new LinkedHashSet<>();
        for (String record : response.split(ZhiMeshConstant.GRAPH_RECORD_DELIMITER)) {
            String cleaned = record.trim()
                    .replace(ZhiMeshConstant.GRAPH_COMPLETION_DELIMITER, "")
                    .replaceAll("^\\(|\\)$", "");
            String[] attributes = cleaned.split(Pattern.quote(ZhiMeshConstant.GRAPH_TUPLE_DELIMITER));
            if (attributes.length < 2) {
                continue;
            }
            String recordType = ZhiMeshStringUtil.clearStr(attributes[0])
                    .replace("\"", "")
                    .replace("'", "")
                    .trim();
            if ("entity".equalsIgnoreCase(recordType)) {
                entities.add(ZhiMeshStringUtil.clearStr(attributes[1])
                        .replaceAll("^[\"']|[\"']$", "")
                        .trim());
            }
        }
        return entities;
    }

    private static String extractJsonObject(String response) {
        String candidate = response.trim()
                .replaceFirst("(?is)^```(?:json)?\\s*", "")
                .replaceFirst("(?is)\\s*```$", "");
        int start = candidate.indexOf('{');
        int end = candidate.lastIndexOf('}');
        return start >= 0 && end > start ? candidate.substring(start, end + 1) : null;
    }

    private static Set<String> normalize(Set<String> entities) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String entity : entities) {
            String value = GraphEntityNameMatcher.normalize(entity);
            if (StringUtils.isNotBlank(value)) {
                normalized.add(value);
            }
        }
        return normalized;
    }

    record ParseResult(Set<String> entities, String format, boolean responseNonBlank) {
    }
}
