package com.pppp.zhimesh.common.rag;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Normalizes the semantic identity stored on graph relationships. */
public final class GraphRelationshipSemantics {

    public static final String DEFAULT_TYPE = "RELATED_TO";
    public static final String DEFAULT_STATUS = "ASSERTED";
    public static final Set<String> ALLOWED_STATUSES = Set.of(
            "ASSERTED", "PLANNED", "PROPOSED", "HISTORICAL", "DISPUTED", "CONDITIONAL");

    private static final Map<String, String> TYPE_ALIASES = Map.ofEntries(
            Map.entry("IS_PART_OF", "PART_OF"),
            Map.entry("BELONGS_TO", "PART_OF"),
            Map.entry("IS_SUBSIDIARY_OF", "SUBSIDIARY_OF"),
            Map.entry("LOCATED_AT", "LOCATED_IN"),
            Map.entry("BASED_IN", "LOCATED_IN"),
            Map.entry("COLLABORATES_WITH", "COOPERATES_WITH"),
            Map.entry("PARTNERS_WITH", "COOPERATES_WITH"),
            Map.entry("PARTNER_OF", "COOPERATES_WITH"),
            Map.entry("PROVIDES_SERVICE_TO", "PROVIDES_TO"),
            Map.entry("SUPPLIES", "SUPPLIES_TO"),
            Map.entry("SUPPLIER_OF", "SUPPLIES_TO"),
            Map.entry("OWNS", "OWNS"),
            Map.entry("OWNED_BY", "OWNED_BY"));

    private GraphRelationshipSemantics() {
    }

    public static String normalizeType(String value) {
        String normalized = StringUtils.defaultString(value, DEFAULT_TYPE)
                .trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isEmpty()) {
            return DEFAULT_TYPE;
        }
        return TYPE_ALIASES.getOrDefault(normalized, normalized);
    }

    public static boolean isValidRawType(String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*");
    }

    public static String normalizeStatus(String value) {
        String normalized = StringUtils.defaultString(value, DEFAULT_STATUS)
                .trim().toUpperCase(Locale.ROOT);
        return ALLOWED_STATUSES.contains(normalized) ? normalized : DEFAULT_STATUS;
    }
}
