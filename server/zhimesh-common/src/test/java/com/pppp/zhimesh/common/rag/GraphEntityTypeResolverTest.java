package com.pppp.zhimesh.common.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphEntityTypeResolverTest {

    @Test
    void correctsCompanyMisclassifiedAsProduct() {
        assertEquals("ORGANIZATION", GraphEntityTypeResolver.resolve(
                "曜穹机器人", "PRODUCT", "曜穹机器人是一家工业机器人公司"));
    }

    @Test
    void correctsNamedModelMisclassifiedAsOrganization() {
        assertEquals("PRODUCT", GraphEntityTypeResolver.resolve(
                "赤脊七型", "ORGANIZATION", "赤脊七型是该公司发布的巡检产品"));
    }

    @Test
    void resolvesDuplicateCandidatesUsingCombinedEvidence() {
        assertEquals("ORGANIZATION", GraphEntityTypeResolver.resolve(
                "曜穹机器人", List.of("PRODUCT", "ORGANIZATION"),
                "一家从事机器人研发的公司"));
    }

    @Test
    void keepsModelTypeWhenThereIsNoStrongContradictingEvidence() {
        assertEquals("CONCEPT", GraphEntityTypeResolver.resolve(
                "潮汐环能", "CONCEPT", "档案中的核心术语"));
    }

    @Test
    void doesNotTurnPersonIntoOrganizationBecauseDescriptionMentionsCompany() {
        assertEquals("PERSON", GraphEntityTypeResolver.resolve(
                "宋知遥", "PERSON", "鹤纹感知材料公司的创始人，早期从事低温复合膜研究"));
    }

    @Test
    void recognizesLocationFromItsOwnNameInsteadOfContextualCompanyWord() {
        assertEquals("LOCATION", GraphEntityTypeResolver.resolve(
                "鹤川材料走廊", "LOCATION", "鹤纹感知材料公司研发中心所在地"));
    }

    @Test
    void recognizesDocumentAndProcessNames() {
        assertEquals("DOCUMENT", GraphEntityTypeResolver.resolve(
                "长期供应合同", "ORGANIZATION", "鹤纹感知材料公司与曜穹机器人开展合作所依据的合同"));
        assertEquals("PROCESS", GraphEntityTypeResolver.resolve(
                "鹤羽触觉融合计划", "ORGANIZATION", "双方共同启动的训练和控制研究计划"));
    }

    @Test
    void stabilizesCloudNetworkTypeAcrossDocuments() {
        assertEquals("SYSTEM", GraphEntityTypeResolver.resolve(
                "浮灯云网", "ORGANIZATION", "跨区域数据交换与调度网络"));
    }

    @Test
    void recognizesBusinessEventsWithoutTreatingParticipantsAsTheEntity() {
        assertEquals("EVENT", GraphEntityTypeResolver.resolve(
                "收购谈判", "ORGANIZATION", "白塔精工提出但未进入董事会表决的谈判"));
        assertEquals("EVENT", GraphEntityTypeResolver.resolve(
                "董事会正式表决", "ORGANIZATION", "收购事项未进入的决策环节"));
        assertEquals("EVENT", GraphEntityTypeResolver.resolve(
                "收购鹤纹消费传感部门", "ORGANIZATION", "白塔精工提出的收购事项"));
        assertEquals("ORGANIZATION", GraphEntityTypeResolver.resolve(
                "鹤纹消费传感部门", "EVENT", "鹤纹感知材料公司的业务部门"));
    }
}
