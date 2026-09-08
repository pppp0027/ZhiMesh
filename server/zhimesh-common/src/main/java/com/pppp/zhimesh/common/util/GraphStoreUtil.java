package com.pppp.zhimesh.common.util;

import com.pppp.zhimesh.common.vo.GraphSearchCondition;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

public class GraphStoreUtil {

    private GraphStoreUtil() {
    }

    /**
     * WHERE clause plus the matching Cypher parameter bindings for one search
     * condition, built in a single pass so placeholders and values can never
     * drift apart. Both {@link #buildWhereClause} and {@link #buildWhereArgs}
     * delegate here; the pass is deterministic for one filter tree, so the two
     * delegating calls always agree on placeholder names.
     */
    public record WhereClauseAndArgs(String clause, Map<String, Object> args) {
    }

    public static WhereClauseAndArgs buildWhere(GraphSearchCondition search, String alias) {
        if (null == search) {
            return new WhereClauseAndArgs(StringUtils.EMPTY, Collections.emptyMap());
        }
        StringBuilder whereClause = new StringBuilder();
        Map<String, Object> result = new HashMap<>();
        if (CollectionUtils.isNotEmpty(search.getNames())) {
            List<String> nameArgs = new ArrayList<>();
            for (int i = 0; i < search.getNames().size(); i++) {
                nameArgs.add("$" + alias + "_name_" + i);
                result.put(alias + "_name_" + i, search.getNames().get(i));
            }
            whereClause.append(String.format("(%s.name in [%s])", alias, String.join(",", nameArgs)));
        }
        if (null != search.getMetadataFilter()) {
            if (!whereClause.isEmpty()) {
                whereClause.append(" and ");
            }
            ZhiMeshApacheAgeJSONFilterMapper adiJSONFilterMapper = new ZhiMeshApacheAgeJSONFilterMapper("metadata");
            adiJSONFilterMapper.setAlias(alias);
            String metadataWhereClause = adiJSONFilterMapper.map(search.getMetadataFilter());
            whereClause.append(metadataWhereClause);
            result.putAll(adiJSONFilterMapper.getParameterArgs());
        }

        return new WhereClauseAndArgs(whereClause.toString(), result);
    }

    public static String buildWhereClause(GraphSearchCondition search, String alias) {
        return buildWhere(search, alias).clause();
    }

    public static Map<String, Object> buildWhereArgs(GraphSearchCondition search, String alias) {
        return buildWhere(search, alias).args();
    }

    public static String buildSetClause(Map<String, Object> metadata) {
//Apache AGE does not support updating Map or List in properties directly, must replace entirely
        //Apache AGE不支持直接更新property中的Map或List，只能直接替换，否则会出现异常：ERROR:  SET clause doesn't not support updating maps or lists in a property
        StringBuilder setClause = new StringBuilder();
        if (MapUtils.isNotEmpty(metadata)) {
            setClause.append(",v.metadata=$new_metadata");
        }
        return setClause.toString();
    }

    public static Map<String, Object> buildSetArgs(Map<String, Object> metadata) {
        Map<String, Object> result = new HashMap<>();
        if (MapUtils.isNotEmpty(metadata)) {
            result.put("new_metadata", metadata);
        }

        return result;
    }
}
