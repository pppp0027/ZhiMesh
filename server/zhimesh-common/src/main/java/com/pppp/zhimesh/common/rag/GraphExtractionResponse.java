package com.pppp.zhimesh.common.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import com.pppp.zhimesh.common.util.JsonUtil;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GraphExtractionResponse {

    private static final Set<String> ALLOWED_TYPES = Set.of(ZhiMeshConstant.GRAPH_ENTITY_EXTRACTION_ENTITY_TYPES);
    private static final Pattern LATIN_PROSE = Pattern.compile(
            "[A-Za-z][A-Za-z0-9'’.-]*(?:\\s+[A-Za-z][A-Za-z0-9'’.-]*){1,}");
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z]+");
    private static final int MIN_STANDALONE_SALIENCE = 5;
    private static final Set<String> GENERIC_PROPERTY_NAMES = Set.of(
            "现金", "品牌", "现金储备", "控股子公司", "矩阵生态独立单元",
            "设备可调用时长", "实际放电时长");
    private static final Pattern GENERIC_PROPERTY_SUFFIX = Pattern.compile(
            ".*(?:占比|比例|金额|数量|时长|状态|口号|统计口径|阈值|数值)$");

    private GraphExtractionResponse() {
    }

    public static String parseToLegacyFormat(String response) {
        GraphExtractionPayload payload = parsePayload(response);

        List<String> records = new ArrayList<>();
        Set<String> relationshipEndpoints = relationshipEndpoints(payload.relationships);
        if (payload.entities != null) {
            for (Entity entity : payload.entities) {
                if (isBlank(entity.name) || isBlank(entity.type)) {
                    continue;
                }
                String type = entity.type.trim().toUpperCase(Locale.ROOT);
                // Keep unknown model output explicit on the legacy transport.
                // Persistence is refused downstream, and the quality gate below
                // forces a repair (or a document failure) before that happens.
                if (!ALLOWED_TYPES.contains(type)) {
                    type = GraphEntityTypeResolver.UNKNOWN_TYPE;
                }
                int salience = clamp(entity.salience == null ? MIN_STANDALONE_SALIENCE : entity.salience,
                        1, 10);
                if (!relationshipEndpoints.contains(normalizeName(entity.name))
                        && (salience < MIN_STANDALONE_SALIENCE
                        || isGenericPropertyEntity(entity.name))) {
                    continue;
                }
                String canonicalName = isBlank(entity.canonicalName)
                        ? entity.name.trim() : entity.canonicalName.trim();
                List<String> aliases = normalizeAliases(entity.aliases, entity.name, canonicalName);
                Map<String, Object> properties = entity.properties == null
                        ? Map.of() : new LinkedHashMap<>(entity.properties);
                records.add(record("entity", entity.name, type, defaultString(entity.description),
                        canonicalName, JsonUtil.toJson(aliases), JsonUtil.toJson(properties),
                        String.valueOf(salience)));
            }
        }
        if (payload.relationships != null) {
            for (Relationship relationship : payload.relationships) {
                if (isBlank(relationship.source) || isBlank(relationship.target)) {
                    continue;
                }
                double weight = clamp(relationship.weight == null ? 1D : relationship.weight, 0D, 10D);
                String relationType = GraphRelationshipSemantics.normalizeType(relationship.type);
                boolean polarity = relationship.polarity == null || relationship.polarity;
                String status = GraphRelationshipSemantics.normalizeStatus(relationship.status);
                Map<String, Object> properties = relationship.properties == null
                        ? Map.of() : new LinkedHashMap<>(relationship.properties);
                records.add(record("relationship", relationship.source, relationship.target,
                        defaultString(relationship.description), String.valueOf(weight), relationType,
                        String.valueOf(polarity), status, JsonUtil.toJson(properties)));
            }
        }
        // An empty array is a valid answer for an informational fragment with
        // no extractable entity or relationship. The caller skips that fragment
        // while continuing to index the rest of the document.
        if (records.isEmpty()) {
            return "";
        }
        return String.join(ZhiMeshConstant.GRAPH_RECORD_DELIMITER, records);
    }

    /**
     * Performs deterministic checks before any model output reaches the graph.
     * The caller (GraphRag's self-healing loop) collects the issues, repairs
     * with them spelled out for the model, and fails closed itself once its
     * attempt budget is exhausted.
     */
    public static List<String> qualityIssues(String response, String inputText) {
        GraphExtractionPayload payload = parsePayload(response);
        LinkedHashSet<String> issues = new LinkedHashSet<>();
        Map<String, String> entityTypes = new LinkedHashMap<>();

        if (payload.entities != null) {
            for (Entity entity : payload.entities) {
                if (isBlank(entity.name) || isBlank(entity.type)) {
                    issues.add("entity name/type is empty");
                    continue;
                }
                if (isBlank(entity.description)) {
                    issues.add("entity description is empty: " + entity.name);
                }
                if (entity.salience != null && (entity.salience < 1 || entity.salience > 10)) {
                    issues.add("entity salience must be between 1 and 10: " + entity.name);
                }
                String normalizedName = normalizeName(entity.name);
                String normalizedType = entity.type.trim().toUpperCase(Locale.ROOT);
                // An UNKNOWN (or arbitrary) type must not reach the graph silently.
                // Flagging it here drives the controlled repair request; a type that
                // is still unresolved after the repair fails the whole document.
                if (!ALLOWED_TYPES.contains(normalizedType)) {
                    issues.add("entity type must be one of the core types, got "
                            + normalizedType + ": " + entity.name);
                }
                String previousType = entityTypes.putIfAbsent(normalizedName, normalizedType);
                if (previousType != null && !previousType.equals(normalizedType)) {
                    issues.add("same entity has conflicting types: " + entity.name
                            + " (" + previousType + "/" + normalizedType + ")");
                }
                if (hasUnexpectedEnglishProse(entity.description, inputText)) {
                    issues.add("entity description contains unexpected English prose: " + entity.name);
                }
            }
        }

        if (payload.relationships != null) {
            for (Relationship relationship : payload.relationships) {
                if (isBlank(relationship.source) || isBlank(relationship.target)) {
                    issues.add("relationship endpoint is empty");
                    continue;
                }
                if (!entityTypes.containsKey(normalizeName(relationship.source))
                        || !entityTypes.containsKey(normalizeName(relationship.target))) {
                    issues.add("relationship endpoint is missing from entities: "
                            + relationship.source + " -> " + relationship.target);
                }
                if (isBlank(relationship.description)) {
                    issues.add("relationship description is empty: "
                            + relationship.source + " -> " + relationship.target);
                }
                if (!GraphRelationshipSemantics.isValidRawType(relationship.type)) {
                    issues.add("relationship type is empty or invalid: "
                            + relationship.source + " -> " + relationship.target);
                }
                if (relationship.polarity == null) {
                    issues.add("relationship polarity is empty: "
                            + relationship.source + " -> " + relationship.target);
                }
                if (isBlank(relationship.status)
                        || !GraphRelationshipSemantics.ALLOWED_STATUSES.contains(
                        relationship.status.trim().toUpperCase(Locale.ROOT))) {
                    issues.add("relationship status is empty or invalid: "
                            + relationship.source + " -> " + relationship.target);
                }
                if (relationship.weight != null
                        && (relationship.weight < 0D || relationship.weight > 10D)) {
                    issues.add("relationship weight must be between 0 and 10: "
                            + relationship.source + " -> " + relationship.target);
                }
                if (hasUnexpectedEnglishProse(relationship.description, inputText)) {
                    issues.add("relationship description contains unexpected English prose: "
                            + relationship.source + " -> " + relationship.target);
                }
            }
        }
        return List.copyOf(issues);
    }

    private static GraphExtractionPayload parsePayload(String response) {
        String json = unwrapJson(response);
        GraphExtractionPayload payload = JsonUtil.fromJson(json, GraphExtractionPayload.class);
        if (payload == null) {
            throw new IllegalArgumentException("Graph extraction response is not valid JSON");
        }
        return payload;
    }

    private static boolean hasUnexpectedEnglishProse(String description, String inputText) {
        if (isBlank(description) || !isPrimarilyChinese(inputText)) {
            return false;
        }
        int latinWords = countLatinWords(description);
        if (!containsCjk(description) && latinWords >= 1) {
            return true;
        }
        String normalizedInput = normalizeLatin(inputText);
        Matcher matcher = LATIN_PROSE.matcher(description);
        while (matcher.find()) {
            if (!normalizedInput.contains(normalizeLatin(matcher.group()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPrimarilyChinese(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        long cjk = value.codePoints().filter(GraphExtractionResponse::isCjk).count();
        long latin = value.codePoints().filter(codePoint ->
                codePoint <= Character.MAX_VALUE && Character.isLetter((char) codePoint) && codePoint < 128).count();
        return cjk >= 4 && cjk * 2 >= latin;
    }

    private static boolean containsCjk(String value) {
        return value != null && value.codePoints().anyMatch(GraphExtractionResponse::isCjk);
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN;
    }

    private static int countLatinWords(String value) {
        if (StringUtils.isBlank(value)) {
            return 0;
        }
        int count = 0;
        Matcher matcher = LATIN_WORD.matcher(value);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String normalizeLatin(String value) {
        return defaultString(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String normalizeName(String value) {
        return defaultString(value).trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static Set<String> relationshipEndpoints(List<Relationship> relationships) {
        LinkedHashSet<String> endpoints = new LinkedHashSet<>();
        if (relationships == null) {
            return endpoints;
        }
        for (Relationship relationship : relationships) {
            if (!isBlank(relationship.source)) {
                endpoints.add(normalizeName(relationship.source));
            }
            if (!isBlank(relationship.target)) {
                endpoints.add(normalizeName(relationship.target));
            }
        }
        return endpoints;
    }

    private static List<String> normalizeAliases(List<String> values, String name,
                                                 String canonicalName) {
        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        if (values != null) {
            for (String value : values) {
                if (!isBlank(value)) {
                    aliases.putIfAbsent(normalizeName(value), value.trim());
                }
            }
        }
        if (!isBlank(name) && !normalizeName(name).equals(normalizeName(canonicalName))) {
            aliases.putIfAbsent(normalizeName(name), name.trim());
        }
        aliases.remove(normalizeName(canonicalName));
        return List.copyOf(aliases.values());
    }

    private static boolean isGenericPropertyEntity(String name) {
        String normalized = defaultString(name).trim();
        return GENERIC_PROPERTY_NAMES.contains(normalized)
                || GENERIC_PROPERTY_SUFFIX.matcher(normalized).matches();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String unwrapJson(String response) {
        if (StringUtils.isBlank(response)) {
            throw new IllegalArgumentException("Graph extraction response is empty");
        }
        String value = response.trim();
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                value = value.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Graph extraction response does not contain a JSON object");
        }
        return value.substring(start, end + 1);
    }

    private static String record(String recordType, String... values) {
        StringBuilder builder = new StringBuilder("(\"").append(recordType).append("\"");
        for (String value : values) {
            builder.append(ZhiMeshConstant.GRAPH_TUPLE_DELIMITER).append(clean(value));
        }
        return builder.append(')').toString();
    }

    private static String clean(String value) {
        return defaultString(value)
                .replace(ZhiMeshConstant.GRAPH_TUPLE_DELIMITER, " ")
                .replace(ZhiMeshConstant.GRAPH_RECORD_DELIMITER, " ")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
    }

    private static boolean isBlank(String value) {
        return StringUtils.isBlank(value);
    }

    private static String defaultString(String value) {
        return StringUtils.defaultString(value);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GraphExtractionPayload {
        public List<Entity> entities;
        public List<Relationship> relationships;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entity {
        public String name;
        @com.fasterxml.jackson.annotation.JsonProperty("canonical_name")
        public String canonicalName;
        public List<String> aliases;
        public String type;
        public String description;
        public Map<String, Object> properties;
        public Integer salience;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Relationship {
        public String source;
        public String target;
        public String type;
        public Boolean polarity;
        public String status;
        public String description;
        public Double weight;
        public Map<String, Object> properties;
    }
}
