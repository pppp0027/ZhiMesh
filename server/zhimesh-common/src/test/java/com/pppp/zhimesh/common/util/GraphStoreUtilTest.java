package com.pppp.zhimesh.common.util;

import com.pppp.zhimesh.common.vo.GraphSearchCondition;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.logical.And;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphStoreUtilTest {

    private static Set<String> placeholders(String clause) {
        Matcher matcher = Pattern.compile("\\$(\\w+)").matcher(clause);
        Set<String> names = new HashSet<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    @Test
    void emptySearchYieldsEmptyClauseAndArgs() {
        GraphStoreUtil.WhereClauseAndArgs where = GraphStoreUtil.buildWhere(null, "v");
        assertEquals("", where.clause());
        assertTrue(where.args().isEmpty());
    }

    @Test
    void singlePassBindsNamesAndMetadataTogether() {
        GraphSearchCondition search = GraphSearchCondition.builder()
                .names(List.of("知识图谱"))
                .metadataFilter(new IsEqualTo("kb_uuid", "kb-123"))
                .build();

        GraphStoreUtil.WhereClauseAndArgs where = GraphStoreUtil.buildWhere(search, "v");

        // Every placeholder referenced by the clause has a bound value, and vice versa.
        assertEquals(placeholders(where.clause()), where.args().keySet());
        assertTrue(where.clause().contains("$v_name_0"));
        assertTrue(where.clause().contains("$v_metadata_kb_uuid_0"));
        assertEquals("知识图谱", where.args().get("v_name_0"));
        assertEquals("kb-123", where.args().get("v_metadata_kb_uuid_0"));
        assertFalse(where.clause().contains("kb-123"), "value must not leak into the clause");
    }

    @Test
    void delegateMethodsAgreeOnPlaceholders() {
        GraphSearchCondition search = GraphSearchCondition.builder()
                .names(List.of("知识图谱", "图谱"))
                .metadataFilter(new IsEqualTo("kb_uuid", "kb-123"))
                .build();

        String clause = GraphStoreUtil.buildWhereClause(search, "v");
        var args = GraphStoreUtil.buildWhereArgs(search, "v");

        assertEquals(placeholders(clause), args.keySet());
        assertEquals("kb-123", args.get("v_metadata_kb_uuid_0"));
    }

    @Test
    void quoteInjectionAttemptStaysInParameter() {
        String malicious = "x' or '1'='1";
        GraphSearchCondition search = GraphSearchCondition.builder()
                .metadataFilter(new IsEqualTo("kb_uuid", malicious))
                .build();

        GraphStoreUtil.WhereClauseAndArgs where = GraphStoreUtil.buildWhere(search, "v");

        assertFalse(where.clause().contains("'1'"), "injected literal must not reach the clause");
        assertFalse(where.clause().contains(malicious));
        assertEquals(malicious, where.args().get("v_metadata_kb_uuid_0"));
    }

    @Test
    void numericEqualityStaysInlined() {
        GraphSearchCondition search = GraphSearchCondition.builder()
                .metadataFilter(new IsEqualTo("level", 5))
                .build();

        GraphStoreUtil.WhereClauseAndArgs where = GraphStoreUtil.buildWhere(search, "v");

        // Numeric values are deliberately inlined: JsonUtil serializes boxed
        // Long as a string, which would break agtype numeric comparison as a
        // parameter. Numeric literals carry no quote-injection surface.
        assertTrue(where.clause().contains("v.metadata.level = 5"));
        assertTrue(where.args().isEmpty());
    }

    @Test
    void duplicateKeysGetDistinctPlaceholders() {
        GraphSearchCondition search = GraphSearchCondition.builder()
                .metadataFilter(new And(
                        new IsEqualTo("kb_uuid", "a"),
                        new IsEqualTo("kb_uuid", "b")))
                .build();

        GraphStoreUtil.WhereClauseAndArgs where = GraphStoreUtil.buildWhere(search, "v");

        assertTrue(where.clause().contains("$v_metadata_kb_uuid_0"));
        assertTrue(where.clause().contains("$v_metadata_kb_uuid_1"));
        assertEquals("a", where.args().get("v_metadata_kb_uuid_0"));
        assertEquals("b", where.args().get("v_metadata_kb_uuid_1"));
    }
}
