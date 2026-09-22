package com.pppp.zhimesh.common.rag.bm25;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Bm25RepositorySqlTest {

    @Test
    void searchSqlScopesCanonicalActiveDocumentsAndUsesOkapiFormula() {
        String sql = Bm25Repository.SEARCH_SQL.replaceAll("\\s+", " ").toLowerCase();

        assertContains(sql, "where d.kb_uuid in (:kbuuids)");
        assertContains(sql, "ib.index_type = 'fulltext'");
        assertContains(sql, "ib.status = 'active'");
        assertContains(sql, "ib.is_active = true");
        assertContains(sql, "ib.model_identity = :analyzerversion");
        assertContains(sql, "d.analyzer_version = :analyzerversion");
        assertContains(sql, "i.fulltext_chunk_set_uuid = i.active_chunk_set_uuid");
        assertContains(sql, "i.fulltext_chunk_set_uuid = d.chunk_set_uuid");

        assertContains(sql,
                "ln(1.0 + ( (s.document_count - df.document_frequency + 0.5) / (df.document_frequency + 0.5) ))");
        assertContains(sql, "p.term_frequency * (:k1 + 1.0)");
        assertContains(sql, "p.term_frequency + :k1 * (");
        assertContains(sql, "1.0 - :b + :b * d.document_length / nullif(s.average_document_length, 0.0)");
        assertContains(sql, "s.document_count < :dffiltermindocuments");
        assertContains(sql,
                "df.document_frequency / s.document_count <= :maxdocumentfrequencyratio");
        assertContains(sql, "order by bm25_score desc, chunk_uuid asc");
        assertContains(sql, "limit :limit");
        assertContains(sql, "from top_scored s join adi_knowledge_base_chunk c");
        assertContains(sql, "c.uuid = s.chunk_uuid");

        int limit = sql.indexOf("limit :limit");
        int contentJoin = sql.indexOf("join adi_knowledge_base_chunk c");
        assertTrue(limit >= 0 && contentJoin > limit,
                "Canonical content must be joined only after scoring and Top-K limiting");
        String beforeLimit = sql.substring(0, limit);
        assertTrue(!beforeLimit.contains("c.content"),
                "Canonical content must not participate in pre-limit scoring/grouping");
    }

    @Test
    void physicalPostingCleanupUsesBuildOwnershipNotDocumentRows() {
        String sql = Bm25Repository.DELETE_POSTINGS_FOR_ITEM_SQL
                .replaceAll("\\s+", " ").toLowerCase();

        assertContains(sql, "delete from adi_knowledge_base_bm25_posting p");
        assertContains(sql, "using adi_knowledge_base_index_build ib");
        assertContains(sql, "p.index_build_uuid = ib.uuid");
        assertContains(sql, "ib.kb_item_uuid = :kbitemuuid");
        assertContains(sql, "ib.index_type = 'fulltext'");
        assertTrue(!sql.contains("adi_knowledge_base_bm25_document"),
                "Orphan posting cleanup must not depend on a surviving document row");
    }

    @Test
    void knowledgeBasePostingCleanupFiltersByKbOwnershipThroughBuilds() {
        String sql = Bm25Repository.DELETE_POSTINGS_FOR_KB_SQL
                .replaceAll("\\s+", " ").toLowerCase();

        assertContains(sql, "delete from adi_knowledge_base_bm25_posting p");
        assertContains(sql, "using adi_knowledge_base_index_build ib");
        assertContains(sql, "p.index_build_uuid = ib.uuid");
        assertContains(sql, "ib.kb_uuid = :kbuuid");
        assertContains(sql, "ib.index_type = 'fulltext'");
        assertTrue(!sql.contains("adi_knowledge_base_bm25_document"),
                "Orphan posting cleanup must not depend on a surviving document row");
    }

    private static void assertContains(String sql, String expected) {
        assertTrue(sql.contains(expected), () -> "Expected SQL fragment: " + expected + "\nActual SQL: " + sql);
    }
}
