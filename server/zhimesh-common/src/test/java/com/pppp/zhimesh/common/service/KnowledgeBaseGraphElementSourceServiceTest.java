package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.entity.KnowledgeBaseGraphElementSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeBaseGraphElementSourceServiceTest {

    @Test
    void rebuildsMaterializedPropertiesFromRemainingContributions() {
        KnowledgeBaseGraphElementSource first = source(
                "kb", "doc-1", "seg-1", "第一篇文档的中文描述", 3D);
        KnowledgeBaseGraphElementSource second = source(
                "kb", "doc-2", "seg-2", "第二篇文档的中文描述", 5D);

        var all = KnowledgeBaseGraphElementSourceService.aggregateSources(List.of(first, second));
        var afterFirstDocumentRemoval = KnowledgeBaseGraphElementSourceService.aggregateSources(
                List.of(second));

        assertEquals("第一篇文档的中文描述\n第二篇文档的中文描述", all.description());
        assertEquals("seg-1,seg-2", all.textSegmentId());
        assertEquals(5D, all.weight());
        assertEquals("doc-1,doc-2", all.metadata().get("kb_item_uuid"));
        assertEquals("第二篇文档的中文描述", afterFirstDocumentRemoval.description());
        assertEquals("seg-2", afterFirstDocumentRemoval.textSegmentId());
        assertEquals("doc-2", afterFirstDocumentRemoval.metadata().get("kb_item_uuid"));
    }

    @Test
    void aggregatesCanonicalAliasesPropertiesAndSemanticEdgeFields() {
        KnowledgeBaseGraphElementSource shortName = source(
                "kb", "doc-1", "seg-1", "简称描述", 8D);
        shortName.setContributionCanonicalName("曜穹机器人");
        shortName.setContributionAliases("[]");
        shortName.setContributionProperties("{\"地区\":\"北部\"}");
        shortName.setContributionSalience(8D);
        shortName.setRelationType("COOPERATES_WITH");
        shortName.setRelationPolarity(true);
        shortName.setRelationStatus("ASSERTED");
        KnowledgeBaseGraphElementSource fullName = source(
                "kb", "doc-2", "seg-2", "全称描述", 16D);
        fullName.setContributionCanonicalName("曜穹机器人股份公司");
        fullName.setContributionAliases("[\"曜穹机器人\"]");
        fullName.setContributionProperties("{\"成立年份\":2024}");
        fullName.setContributionSalience(9D);
        fullName.setRelationType("COOPERATES_WITH");
        fullName.setRelationPolarity(true);
        fullName.setRelationStatus("ASSERTED");

        var aggregate = KnowledgeBaseGraphElementSourceService.aggregateSources(
                List.of(shortName, fullName));

        assertEquals("曜穹机器人股份公司", aggregate.canonicalName());
        assertEquals(List.of("曜穹机器人"), aggregate.aliases());
        assertEquals(Map.of("地区", "北部", "成立年份", 2024), aggregate.properties());
        assertEquals(9D, aggregate.salience());
        assertEquals(10D, aggregate.weight());
        assertEquals("COOPERATES_WITH", aggregate.relationType());
        assertEquals(true, aggregate.polarity());
        assertEquals("ASSERTED", aggregate.status());
        assertEquals(2, aggregate.sourceCount());
    }

    private static KnowledgeBaseGraphElementSource source(String kbUuid, String itemUuid,
                                                           String segmentUuid, String description,
                                                           Double weight) {
        KnowledgeBaseGraphElementSource source = new KnowledgeBaseGraphElementSource();
        source.setKbUuid(kbUuid);
        source.setKbItemUuid(itemUuid);
        source.setGraphSegmentUuid(segmentUuid);
        source.setContributionDescription(description);
        source.setContributionWeight(weight);
        return source;
    }
}
