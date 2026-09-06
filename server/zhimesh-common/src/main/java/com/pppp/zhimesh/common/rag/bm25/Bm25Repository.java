package com.pppp.zhimesh.common.rag.bm25;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** PostgreSQL persistence and scoring queries for the centralized BM25 index. */
@Repository
public class Bm25Repository {

    static final String SEARCH_SQL = """
            WITH scoped_documents AS (
                SELECT d.chunk_uuid,
                       d.index_build_uuid,
                       d.kb_uuid,
                       d.kb_item_uuid,
                       d.chunk_set_uuid,
                       d.document_length
                  FROM adi_knowledge_base_bm25_document d
                  JOIN adi_knowledge_base_index_build ib
                    ON ib.uuid = d.index_build_uuid
                   AND ib.index_type = 'FULLTEXT'
                   AND ib.status = 'ACTIVE'
                   AND ib.is_active = true
                   AND ib.model_identity = :analyzerVersion
                  JOIN adi_knowledge_base_item i
                    ON i.uuid = d.kb_item_uuid
                   AND i.kb_uuid = d.kb_uuid
                   AND i.active_chunk_set_uuid <> ''
                   AND i.fulltext_chunk_set_uuid = i.active_chunk_set_uuid
                   AND i.fulltext_chunk_set_uuid = d.chunk_set_uuid
                 WHERE d.kb_uuid IN (:kbUuids)
                   AND d.analyzer_version = :analyzerVersion
            ), corpus_stats AS (
                SELECT COUNT(*)::double precision AS document_count,
                       AVG(document_length)::double precision AS average_document_length
                  FROM scoped_documents
            ), term_document_frequency AS (
                SELECT p.term,
                       COUNT(DISTINCT p.chunk_uuid)::double precision AS document_frequency
                  FROM adi_knowledge_base_bm25_posting p
                  JOIN scoped_documents d
                    ON d.index_build_uuid = p.index_build_uuid
                   AND d.chunk_uuid = p.chunk_uuid
                 WHERE p.term IN (:terms)
                 GROUP BY p.term
            ), scored AS (
                SELECT d.chunk_uuid,
                       d.index_build_uuid,
                       d.kb_uuid,
                       d.kb_item_uuid,
                       d.chunk_set_uuid,
                       SUM(
                           LN(1.0 + (
                               (s.document_count - df.document_frequency + 0.5)
                               / (df.document_frequency + 0.5)
                           ))
                           * (
                               p.term_frequency * (:k1 + 1.0)
                               / (
                                   p.term_frequency
                                   + :k1 * (
                                       1.0 - :b
                                       + :b * d.document_length
                                           / NULLIF(s.average_document_length, 0.0)
                                   )
                               )
                           )
                       )::double precision AS bm25_score
                  FROM scoped_documents d
                  JOIN adi_knowledge_base_bm25_posting p
                    ON p.index_build_uuid = d.index_build_uuid
                   AND p.chunk_uuid = d.chunk_uuid
                  JOIN term_document_frequency df ON df.term = p.term
                 CROSS JOIN corpus_stats s
                 WHERE p.term IN (:terms)
                   AND s.document_count > 0
                   AND (
                       s.document_count < :dfFilterMinDocuments
                       OR df.document_frequency / s.document_count <= :maxDocumentFrequencyRatio
                   )
                 GROUP BY d.chunk_uuid, d.index_build_uuid, d.kb_uuid,
                          d.kb_item_uuid, d.chunk_set_uuid
            ), top_scored AS (
                SELECT chunk_uuid, index_build_uuid, kb_uuid, kb_item_uuid,
                       chunk_set_uuid, bm25_score
                  FROM scored
                 WHERE bm25_score > 0
                 ORDER BY bm25_score DESC, chunk_uuid ASC
                 LIMIT :limit
            )
            SELECT s.chunk_uuid, s.index_build_uuid, s.kb_uuid, s.kb_item_uuid,
                   s.chunk_set_uuid, c.content, s.bm25_score
              FROM top_scored s
              JOIN adi_knowledge_base_chunk c
                ON c.uuid = s.chunk_uuid
               AND c.chunk_set_uuid = s.chunk_set_uuid
             ORDER BY s.bm25_score DESC, s.chunk_uuid ASC
            """;

    static final String DELETE_POSTINGS_FOR_ITEM_SQL = """
            DELETE FROM adi_knowledge_base_bm25_posting p
             USING adi_knowledge_base_index_build ib
             WHERE p.index_build_uuid = ib.uuid
               AND ib.kb_item_uuid = :kbItemUuid
               AND ib.index_type = 'FULLTEXT'
            """;

    private static final String INSERT_DOCUMENT_SQL = """
            INSERT INTO adi_knowledge_base_bm25_document
                (chunk_uuid, index_build_uuid, analyzer_version, kb_uuid,
                 kb_item_uuid, chunk_set_uuid, document_length, create_time, update_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_POSTING_SQL = """
            INSERT INTO adi_knowledge_base_bm25_posting
                (index_build_uuid, chunk_uuid, term, term_frequency)
            VALUES (?, ?, ?, ?)
            """;

    private final NamedParameterJdbcTemplate namedJdbc;
    private final JdbcTemplate jdbc;

    public Bm25Repository(NamedParameterJdbcTemplate namedJdbc) {
        this.namedJdbc = namedJdbc;
        this.jdbc = namedJdbc.getJdbcTemplate();
    }

    public void createBuildingIndex(Bm25BuildRecord build) {
        String sql = """
                INSERT INTO adi_knowledge_base_index_build
                    (uuid, kb_id, kb_uuid, kb_item_id, kb_item_uuid, chunk_set_uuid,
                     index_type, model_id, model_identity, build_key_hash, prompt_version,
                     graph_release_uuid, graph_namespace, status, attempt, is_active,
                     started_at, create_time, update_time)
                VALUES
                    (:uuid, :kbId, :kbUuid, :kbItemId, :kbItemUuid, :chunkSetUuid,
                     'FULLTEXT', 0, :analyzerVersion, :buildKeyHash, '', '', '',
                     'BUILDING', 1, false, :now, :now, :now)
                """;
        namedJdbc.update(sql, new MapSqlParameterSource()
                .addValue("uuid", build.uuid())
                .addValue("kbId", build.kbId())
                .addValue("kbUuid", build.kbUuid())
                .addValue("kbItemId", build.kbItemId())
                .addValue("kbItemUuid", build.kbItemUuid())
                .addValue("chunkSetUuid", build.chunkSetUuid())
                .addValue("analyzerVersion", build.analyzerVersion())
                .addValue("buildKeyHash", build.buildKeyHash())
                .addValue("now", Timestamp.valueOf(build.startedAt())));
    }

    public Optional<KnowledgeBaseItem> findItem(String kbItemUuid) {
        List<KnowledgeBaseItem> items = namedJdbc.query("""
                SELECT id, kb_id, kb_uuid, uuid, active_chunk_set_uuid
                  FROM adi_knowledge_base_item
                 WHERE uuid = :kbItemUuid
                """, new MapSqlParameterSource("kbItemUuid", kbItemUuid), (resultSet, rowNum) -> {
            KnowledgeBaseItem item = new KnowledgeBaseItem();
            item.setId(resultSet.getLong("id"));
            item.setKbId(resultSet.getLong("kb_id"));
            item.setKbUuid(resultSet.getString("kb_uuid"));
            item.setUuid(resultSet.getString("uuid"));
            item.setActiveChunkSetUuid(resultSet.getString("active_chunk_set_uuid"));
            return item;
        });
        return items.stream().findFirst();
    }

    /** Serializes rebuild/delete publication for one knowledge-base item. */
    public Optional<KnowledgeBaseItem> lockItemForUpdate(String kbItemUuid) {
        List<KnowledgeBaseItem> items = namedJdbc.query("""
                SELECT id, kb_id, kb_uuid, uuid, active_chunk_set_uuid
                  FROM adi_knowledge_base_item
                 WHERE uuid = :kbItemUuid
                   FOR UPDATE
                """, new MapSqlParameterSource("kbItemUuid", kbItemUuid), (resultSet, rowNum) -> {
            KnowledgeBaseItem item = new KnowledgeBaseItem();
            item.setId(resultSet.getLong("id"));
            item.setKbId(resultSet.getLong("kb_id"));
            item.setKbUuid(resultSet.getString("kb_uuid"));
            item.setUuid(resultSet.getString("uuid"));
            item.setActiveChunkSetUuid(resultSet.getString("active_chunk_set_uuid"));
            return item;
        });
        return items.stream().findFirst();
    }

    public Optional<ActiveBm25Build> findActiveBuild(String kbItemUuid, String chunkSetUuid,
                                                      String analyzerVersion, String buildKeyHash) {
        List<ActiveBm25Build> builds = namedJdbc.query("""
                SELECT uuid
                  FROM adi_knowledge_base_index_build
                 WHERE kb_item_uuid = :kbItemUuid
                   AND chunk_set_uuid = :chunkSetUuid
                   AND index_type = 'FULLTEXT'
                   AND model_identity = :analyzerVersion
                   AND build_key_hash = :buildKeyHash
                   AND status = 'ACTIVE'
                   AND is_active = true
                """, new MapSqlParameterSource()
                .addValue("kbItemUuid", kbItemUuid)
                .addValue("chunkSetUuid", chunkSetUuid)
                .addValue("analyzerVersion", analyzerVersion)
                .addValue("buildKeyHash", buildKeyHash),
                (resultSet, rowNum) -> new ActiveBm25Build(resultSet.getString("uuid")));
        return builds.stream().findFirst();
    }

    /** Marks an inconsistent ACTIVE build unusable before an atomic replacement. */
    public void supersedeBuild(String kbItemUuid, String buildUuid) {
        int updated = namedJdbc.update("""
                UPDATE adi_knowledge_base_index_build
                   SET status = 'SUPERSEDED', is_active = false
                 WHERE uuid = :buildUuid
                   AND kb_item_uuid = :kbItemUuid
                   AND index_type = 'FULLTEXT'
                   AND status = 'ACTIVE'
                   AND is_active = true
                """, new MapSqlParameterSource()
                .addValue("kbItemUuid", kbItemUuid)
                .addValue("buildUuid", buildUuid));
        if (updated != 1) {
            throw new IllegalStateException("Unable to supersede inconsistent FULLTEXT build " + buildUuid);
        }
    }

    /** Removes the old physical index inside the caller's publishing transaction. */
    public void deletePhysicalIndexForItem(String kbItemUuid) {
        MapSqlParameterSource params = new MapSqlParameterSource("kbItemUuid", kbItemUuid);
        namedJdbc.update(DELETE_POSTINGS_FOR_ITEM_SQL, params);
        namedJdbc.update("""
                DELETE FROM adi_knowledge_base_bm25_document
                 WHERE kb_item_uuid = :kbItemUuid
                """, params);
    }

    public void insertDocuments(List<Bm25DocumentRecord> documents) {
        if (documents.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT_DOCUMENT_SQL, documents, 500,
                (PreparedStatement statement, Bm25DocumentRecord document) -> {
                    statement.setString(1, document.chunkUuid());
                    statement.setString(2, document.indexBuildUuid());
                    statement.setString(3, document.analyzerVersion());
                    statement.setString(4, document.kbUuid());
                    statement.setString(5, document.kbItemUuid());
                    statement.setString(6, document.chunkSetUuid());
                    statement.setInt(7, document.documentLength());
                    Timestamp now = Timestamp.valueOf(document.createdAt());
                    statement.setTimestamp(8, now);
                    statement.setTimestamp(9, now);
                });
    }

    public void insertPostings(List<Bm25PostingRecord> postings) {
        if (postings.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT_POSTING_SQL, postings, 1000,
                (PreparedStatement statement, Bm25PostingRecord posting) -> {
                    statement.setString(1, posting.indexBuildUuid());
                    statement.setString(2, posting.chunkUuid());
                    statement.setString(3, posting.term());
                    statement.setInt(4, posting.termFrequency());
                });
    }

    public void activateBuild(String kbItemUuid, String buildUuid, String chunkSetUuid,
                              LocalDateTime completedAt) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("kbItemUuid", kbItemUuid)
                .addValue("buildUuid", buildUuid)
                .addValue("chunkSetUuid", chunkSetUuid)
                .addValue("completedAt", Timestamp.valueOf(completedAt));
        namedJdbc.update("""
                UPDATE adi_knowledge_base_index_build
                   SET status = 'SUPERSEDED', is_active = false
                 WHERE kb_item_uuid = :kbItemUuid
                   AND index_type = 'FULLTEXT'
                   AND uuid <> :buildUuid
                   AND is_active = true
                """, params);
        int activated = namedJdbc.update("""
                UPDATE adi_knowledge_base_index_build
                   SET status = 'ACTIVE', is_active = true,
                       completed_at = :completedAt, activated_at = :completedAt
                 WHERE uuid = :buildUuid
                   AND kb_item_uuid = :kbItemUuid
                   AND index_type = 'FULLTEXT'
                   AND status = 'BUILDING'
                """, params);
        if (activated != 1) {
            throw new IllegalStateException("Unable to activate FULLTEXT build " + buildUuid);
        }
        int updated = namedJdbc.update("""
                UPDATE adi_knowledge_base_item
                   SET fulltext_chunk_set_uuid = :chunkSetUuid
                 WHERE uuid = :kbItemUuid
                """, params);
        if (updated != 1) {
            throw new IllegalStateException("Knowledge-base item disappeared while publishing BM25 index");
        }
    }

    public void deleteItemIndex(String kbItemUuid) {
        deletePhysicalIndexForItem(kbItemUuid);
        MapSqlParameterSource params = new MapSqlParameterSource("kbItemUuid", kbItemUuid);
        namedJdbc.update("""
                UPDATE adi_knowledge_base_index_build
                   SET status = 'SUPERSEDED', is_active = false
                 WHERE kb_item_uuid = :kbItemUuid
                   AND index_type = 'FULLTEXT'
                   AND status IN ('PENDING', 'BUILDING', 'READY', 'ACTIVE')
                """, params);
        namedJdbc.update("""
                UPDATE adi_knowledge_base_item
                   SET fulltext_chunk_set_uuid = ''
                 WHERE uuid = :kbItemUuid
                """, params);
    }

    public Set<String> readyKnowledgeBases(Collection<String> kbUuids, String analyzerVersion) {
        if (kbUuids == null || kbUuids.isEmpty()) {
            return Set.of();
        }
        String sql = """
                SELECT i.kb_uuid,
                       COUNT(DISTINCT i.uuid) AS item_count,
                       COUNT(DISTINCT CASE
                           WHEN i.active_chunk_set_uuid <> ''
                            AND i.fulltext_chunk_set_uuid = i.active_chunk_set_uuid
                            AND ib.uuid IS NOT NULL
                           THEN i.uuid END) AS ready_count
                  FROM adi_knowledge_base_item i
                  LEFT JOIN adi_knowledge_base_index_build ib
                    ON ib.kb_item_uuid = i.uuid
                   AND ib.kb_uuid = i.kb_uuid
                   AND ib.index_type = 'FULLTEXT'
                   AND ib.status = 'ACTIVE'
                   AND ib.is_active = true
                   AND ib.model_identity = :analyzerVersion
                   AND ib.chunk_set_uuid = i.fulltext_chunk_set_uuid
                 WHERE i.kb_uuid IN (:kbUuids)
                 GROUP BY i.kb_uuid
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("kbUuids", kbUuids)
                .addValue("analyzerVersion", analyzerVersion);
        LinkedHashSet<String> ready = new LinkedHashSet<>();
        namedJdbc.query(sql, params, resultSet -> {
            long itemCount = resultSet.getLong("item_count");
            long readyCount = resultSet.getLong("ready_count");
            if (itemCount > 0 && itemCount == readyCount) {
                ready.add(resultSet.getString("kb_uuid"));
            }
        });
        return Set.copyOf(ready);
    }

    public List<Bm25SearchHit> search(Set<String> kbUuids, List<String> terms,
                                      String analyzerVersion, double k1, double b, int limit) {
        return search(kbUuids, terms, analyzerVersion, k1, b, limit, 0.85D, 20);
    }

    public List<Bm25SearchHit> search(Set<String> kbUuids, List<String> terms,
                                      String analyzerVersion, double k1, double b, int limit,
                                      double maxDocumentFrequencyRatio,
                                      int documentFrequencyFilterMinDocuments) {
        if (kbUuids == null || kbUuids.isEmpty() || terms == null || terms.isEmpty() || limit <= 0) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("kbUuids", kbUuids)
                .addValue("terms", terms)
                .addValue("analyzerVersion", analyzerVersion)
                .addValue("k1", k1)
                .addValue("b", b)
                .addValue("maxDocumentFrequencyRatio", maxDocumentFrequencyRatio)
                .addValue("dfFilterMinDocuments", documentFrequencyFilterMinDocuments)
                .addValue("limit", limit);
        return namedJdbc.query(SEARCH_SQL, params, (resultSet, rowNum) -> new Bm25SearchHit(
                resultSet.getString("chunk_uuid"),
                resultSet.getString("index_build_uuid"),
                resultSet.getString("kb_uuid"),
                resultSet.getString("kb_item_uuid"),
                resultSet.getString("chunk_set_uuid"),
                resultSet.getString("content"),
                resultSet.getDouble("bm25_score")));
    }

    public int countDocumentsForBuild(String buildUuid) {
        Integer result = jdbc.queryForObject("""
                SELECT COUNT(*) FROM adi_knowledge_base_bm25_document
                 WHERE index_build_uuid = ?
                """, Integer.class, buildUuid);
        return result == null ? 0 : result;
    }
}

record Bm25BuildRecord(String uuid, Long kbId, String kbUuid, Long kbItemId,
                       String kbItemUuid, String chunkSetUuid, String analyzerVersion,
                       String buildKeyHash, LocalDateTime startedAt) {
}

record Bm25DocumentRecord(String chunkUuid, String indexBuildUuid, String analyzerVersion,
                          String kbUuid, String kbItemUuid, String chunkSetUuid,
                          int documentLength, LocalDateTime createdAt) {
}

record Bm25PostingRecord(String indexBuildUuid, String chunkUuid,
                         String term, int termFrequency) {
}

record Bm25SearchHit(String chunkUuid, String indexBuildUuid, String kbUuid,
                     String kbItemUuid, String chunkSetUuid, String content, double score) {
}

record ActiveBm25Build(String uuid) {
}
