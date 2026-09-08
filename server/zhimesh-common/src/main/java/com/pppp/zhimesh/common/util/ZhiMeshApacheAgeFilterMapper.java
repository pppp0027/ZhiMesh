package com.pppp.zhimesh.common.util;

import com.pppp.zhimesh.common.vo.GraphContains;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.*;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.filter.logical.Not;
import dev.langchain4j.store.embedding.filter.logical.Or;

import java.util.AbstractMap.SimpleEntry;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.String.format;

abstract class ZhiMeshApacheAgeFilterMapper {

    protected String alias;

    public void setAlias(String alias) {
        this.alias = alias;
    }

    /**
     * Cypher parameter bindings emitted while mapping, keyed by placeholder
     * name (without the leading "$"). Filled only by comparisons that are
     * parameterized (see {@link #mapEqual}).
     */
    private final Map<String, Object> parameterArgs = new LinkedHashMap<>();

    private int parameterCounter = 0;

    static final Map<Class<?>, String> SQL_TYPE_MAP = Stream.of(
                    new SimpleEntry<>(Integer.class, "int"),
                    new SimpleEntry<>(Long.class, "bigint"),
                    new SimpleEntry<>(Float.class, "float"),
                    new SimpleEntry<>(Double.class, "float8"),
                    new SimpleEntry<>(String.class, "text"),
                    new SimpleEntry<>(UUID.class, "uuid"),
                    new SimpleEntry<>(Boolean.class, "boolean"),
                    // Default
                    new SimpleEntry<>(Object.class, "text"))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    public String map(Filter filter) {
        if (filter instanceof IsEqualTo filterInst) {
            return mapEqual(filterInst);
        } else if (filter instanceof IsNotEqualTo filterInst) {
            return mapNotEqual(filterInst);
        } else if (filter instanceof IsGreaterThan filterInst) {
            return mapGreaterThan(filterInst);
        } else if (filter instanceof IsGreaterThanOrEqualTo filterInst) {
            return mapGreaterThanOrEqual(filterInst);
        } else if (filter instanceof IsLessThan filterInst) {
            return mapLessThan(filterInst);
        } else if (filter instanceof IsLessThanOrEqualTo filterInst) {
            return mapLessThanOrEqual(filterInst);
        } else if (filter instanceof IsIn filterInst) {
            return mapIn(filterInst);
        } else if (filter instanceof IsNotIn filterInst) {
            return mapNotIn(filterInst);
        } else if (filter instanceof And filterInst) {
            return mapAnd(filterInst);
        } else if (filter instanceof Not filterInst) {
            return mapNot(filterInst);
        } else if (filter instanceof Or filterInst) {
            return mapOr(filterInst);
        } else if (filter instanceof GraphContains filterInst) {
            return mapContains(filterInst);
        } else {
            throw new UnsupportedOperationException("Unsupported filter type: " + filter.getClass().getName());
        }
    }

    private String mapEqual(IsEqualTo isEqualTo) {
        String key = formatKey(isEqualTo.key(), isEqualTo.comparisonValue().getClass());
        Object value = isEqualTo.comparisonValue();
        // Parameterize value types that can carry quote-based injection. Numeric
        // values stay inlined: the shared JsonUtil serializes boxed Long as a
        // JSON string, which would break agtype numeric comparison as a parameter.
        if (value instanceof String || value instanceof UUID || value instanceof Boolean) {
            String placeholder = registerParameter(isEqualTo.key(), value);
            return format("%s is not null and %s = %s", key, key, placeholder);
        }
        return format("%s is not null and %s = %s", key, key, formatValue(value));
    }

    /**
     * Registers a comparison value as a Cypher parameter instead of an inlined
     * literal. Placeholder names are counter-based, so repeated keys never
     * collide and clause/args passes stay deterministic for one filter tree.
     */
    private String registerParameter(String key, Object value) {
        String safeKey = key.replaceAll("[^a-zA-Z0-9_]", "_");
        String aliasPrefix = alias == null || alias.isBlank() ? "" : alias + "_";
        String name = aliasPrefix + "metadata_" + safeKey + "_" + parameterCounter++;
        parameterArgs.put(name, value);
        return "$" + name;
    }

    Map<String, Object> getParameterArgs() {
        return parameterArgs;
    }

    private String mapNotEqual(IsNotEqualTo isNotEqualTo) {
        String key = formatKey(isNotEqualTo.key(), isNotEqualTo.comparisonValue().getClass());
        return format("%s is null or %s != %s", key, key,
                formatValue(isNotEqualTo.comparisonValue()));
    }

    private String mapGreaterThan(IsGreaterThan isGreaterThan) {
        return format("%s > %s", formatKey(isGreaterThan.key(), isGreaterThan.comparisonValue().getClass()),
                formatValue(isGreaterThan.comparisonValue()));
    }

    private String mapGreaterThanOrEqual(IsGreaterThanOrEqualTo isGreaterThanOrEqualTo) {
        return format("%s >= %s", formatKey(isGreaterThanOrEqualTo.key(), isGreaterThanOrEqualTo.comparisonValue().getClass()),
                formatValue(isGreaterThanOrEqualTo.comparisonValue()));
    }

    private String mapLessThan(IsLessThan isLessThan) {
        return format("%s < %s", formatKey(isLessThan.key(), isLessThan.comparisonValue().getClass()),
                formatValue(isLessThan.comparisonValue()));
    }

    private String mapLessThanOrEqual(IsLessThanOrEqualTo isLessThanOrEqualTo) {
        return format("%s <= %s", formatKey(isLessThanOrEqualTo.key(), isLessThanOrEqualTo.comparisonValue().getClass()),
                formatValue(isLessThanOrEqualTo.comparisonValue()));
    }

    private String mapIn(IsIn isIn) {
        return format("%s in %s", formatJsonKeyAsString(isIn.key()), formatValuesAsString(isIn.comparisonValues()));
    }

    private String mapNotIn(IsNotIn isNotIn) {
        String key = formatKeyAsString(isNotIn.key());
        return format("%s is null or %s not in %s", key, key, formatValuesAsString(isNotIn.comparisonValues()));
    }

    private String mapAnd(And and) {
        return format("%s and %s", map(and.left()), map(and.right()));
    }

    private String mapNot(Not not) {
        return format("not(%s)", map(not.expression()));
    }

    private String mapOr(Or or) {
        return format("(%s or %s)", map(or.left()), map(or.right()));
    }

    private String mapContains(GraphContains contains) {
        return format("(%s contains %s)", formatKeyAsString(contains.key()), formatValue(contains.value()));
    }

    abstract String formatKey(String key, Class<?> valueType);

    abstract String formatKeyAsString(String key);

    abstract String formatJsonKeyAsString(String key);

    String formatValue(Object value) {
        if (value instanceof String || value instanceof UUID) {
            return "'" + value + "'";
        } else {
            return value.toString();
        }
    }

    String formatValuesAsString(Collection<?> values) {
        return "[" + values.stream().map(v -> String.format("'%s'", v))
                .collect(Collectors.joining(",")) + "]";
    }

    String formatJsonValuesAsString(Collection<?> values) {
        return "[" + values.stream().map(v -> String.format("'%s'", v))
                .collect(Collectors.joining(",")) + "]";
    }
}
