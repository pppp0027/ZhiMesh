package com.pppp.zhimesh.common.rag.profile;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.entity.KnowledgeBase;
import com.pppp.zhimesh.common.entity.KnowledgeBaseItem;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class KnowledgeRouteProfileManifestBuilder {

    private final ZhiMeshProperties properties;

    public KnowledgeRouteProfileManifestBuilder(ZhiMeshProperties properties) {
        this.properties = properties;
    }

    public String build(KnowledgeBase kb, List<KnowledgeBaseItem> items) {
        StringBuilder manifest = new StringBuilder()
                .append(StringUtils.defaultString(kb.getUuid())).append('\n')
                .append(StringUtils.defaultString(kb.getTitle())).append('\n')
                .append(StringUtils.defaultString(kb.getRemark())).append('\n')
                .append(properties.getEmbeddingModel()).append('\n')
                .append(properties.getKnowledgeScopeGate().getGeneratorVersion()).append('\n')
                .append(properties.getKnowledgeScopeGate().getProfileLimit()).append('\n')
                .append(properties.getKnowledgeScopeGate().getCandidatePoolLimit()).append('\n');
        items.stream().sorted(Comparator.comparing(KnowledgeBaseItem::getUuid,
                        Comparator.nullsFirst(String::compareTo)))
                .forEach(item -> manifest
                        .append(StringUtils.defaultString(item.getUuid())).append('|')
                        .append(StringUtils.defaultString(item.getTitle())).append('|')
                        .append(StringUtils.defaultString(item.getBrief())).append('|')
                        .append(KnowledgeRouteProfileCandidateCollector.sha256(
                                StringUtils.defaultString(item.getRemark()))).append('|')
                        .append(StringUtils.defaultString(item.getActiveChunkSetUuid())).append('|')
                        .append(StringUtils.defaultString(item.getEmbeddingChunkSetUuid())).append('\n'));
        return KnowledgeRouteProfileCandidateCollector.sha256(manifest.toString());
    }
}
