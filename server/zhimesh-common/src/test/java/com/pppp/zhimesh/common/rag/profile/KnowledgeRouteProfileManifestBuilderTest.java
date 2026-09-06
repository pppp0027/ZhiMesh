package com.pppp.zhimesh.common.rag.profile;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRouteProfileManifestBuilderTest {

    private final ZhiMeshProperties properties = new ZhiMeshProperties();
    private final KnowledgeRouteProfileManifestBuilder builder =
            new KnowledgeRouteProfileManifestBuilder(properties);

    KnowledgeRouteProfileManifestBuilderTest() {
        properties.setEmbeddingModel("local:bge-small-zh-v1.5");
    }

    @Test
    void identicalInputsProduceTheSameManifestRegardlessOfItemOrder() {
        KnowledgeBase kb = knowledgeBase();
        KnowledgeBaseItem first = item("item-a", "set-a", "差旅制度");
        KnowledgeBaseItem second = item("item-b", "set-b", "报销流程");

        String ordered = builder.build(kb, List.of(first, second));
        String reversed = builder.build(kb, List.of(second, first));

        assertThat(ordered).isEqualTo(reversed).hasSize(64);
    }

    @Test
    void contentChunkSetOrGeneratorChangesInvalidateTheManifest() {
        KnowledgeBase kb = knowledgeBase();
        KnowledgeBaseItem item = item("item-a", "set-a", "差旅制度");
        String original = builder.build(kb, List.of(item));

        item.setActiveChunkSetUuid("set-b");
        String chunkSetChanged = builder.build(kb, List.of(item));
        properties.getKnowledgeScopeGate().setGeneratorVersion("kb-route-profile-v2");
        String generatorChanged = builder.build(kb, List.of(item));

        assertThat(chunkSetChanged).isNotEqualTo(original);
        assertThat(generatorChanged).isNotEqualTo(chunkSetChanged);
    }

    private static KnowledgeBase knowledgeBase() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setUuid("kb-a");
        kb.setTitle("企业制度");
        kb.setRemark("内部流程与规范");
        return kb;
    }

    private static KnowledgeBaseItem item(String uuid, String chunkSetUuid, String title) {
        KnowledgeBaseItem item = new KnowledgeBaseItem();
        item.setUuid(uuid);
        item.setTitle(title);
        item.setBrief(title + "摘要");
        item.setRemark(title + "正文");
        item.setActiveChunkSetUuid(chunkSetUuid);
        item.setEmbeddingChunkSetUuid(chunkSetUuid);
        return item;
    }
}
