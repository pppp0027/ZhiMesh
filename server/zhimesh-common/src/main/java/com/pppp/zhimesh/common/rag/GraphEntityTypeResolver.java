package com.pppp.zhimesh.common.rag;

import com.pppp.zhimesh.common.cosntant.ZhiMeshConstant;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Conservative post-model type resolver. It corrects only strong semantic
 * contradictions (for example, a company labelled PRODUCT) and otherwise
 * keeps the model's core type. The graph may legitimately contain two
 * different objects with the same display name, so this class does not impose
 * a global name-only uniqueness rule.
 */
final class GraphEntityTypeResolver {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            ZhiMeshConstant.GRAPH_ENTITY_EXTRACTION_ENTITY_TYPES);

    /** Transport-only fallback. A vertex with this label must never be persisted. */
    static final String UNKNOWN_TYPE = "UNKNOWN";

    static boolean isUnknown(String type) {
        return UNKNOWN_TYPE.equals(StringUtils.defaultString(type).trim()
                .toUpperCase(Locale.ROOT));
    }
    private static final Pattern PRODUCT_MODEL_NAME = Pattern.compile(
            ".*(?:[0-9一二三四五六七八九十]+型|[A-Za-z0-9-]+系列)$");
    /** Transaction/action phrases must win over an object suffix such as “部门”. */
    private static final Pattern EVENT_ACTION_PHRASE = Pattern.compile(
            "^(?:收购|并购|出售|转让|签约|融资|发布|泄露|复制|调查|改名|重组|停产|投产|终止|启动).+");

    private static final Map<String, List<String>> NAME_SUFFIX_MARKERS = Map.ofEntries(
            Map.entry("ORGANIZATION", List.of("公司", "企业", "集团", "机构", "组织", "团队",
                    "部门", "联合体", "委员会", "协会", "研究院", "大学", "银行")),
            Map.entry("LOCATION", List.of("走廊", "园区", "地区", "城市", "基地", "街道")),
            Map.entry("DOCUMENT", List.of("合同", "文件", "报告", "摘要", "手册", "规范", "标准",
                    "政策", "白皮书", "宣传册", "宣传页", "复核稿")),
            Map.entry("PROCESS", List.of("计划", "流程", "程序", "工序", "操作", "验证")),
            Map.entry("EVENT", List.of("谈判", "表决", "会议", "事故", "发布会", "并购")),
            Map.entry("SYSTEM", List.of("系统", "平台", "沙盒", "云网")),
            Map.entry("SERVICE", List.of("服务")),
            Map.entry("ROLE", List.of("岗位", "角色", "职位"))
    );

    private static final Map<String, List<String>> SELF_DESCRIPTION_MARKERS = Map.ofEntries(
            Map.entry("PERSON", List.of("个人", "自然人", "创始人", "工程师", "董事长", "教授", "研究员")),
            Map.entry("PRODUCT", List.of("产品", "型号", "机型", "设备", "器件", "材料产品")),
            Map.entry("LOCATION", List.of("地点", "地区", "城市", "园区", "走廊", "所在地")),
            Map.entry("DOCUMENT", List.of("合同", "文档", "文件", "报告", "规范", "标准", "手册")),
            Map.entry("PROCESS", List.of("计划", "流程", "工序", "步骤", "程序", "操作")),
            Map.entry("EVENT", List.of("事件", "事故", "会议", "谈判", "表决", "并购")),
            Map.entry("SYSTEM", List.of("软件系统", "业务系统", "系统", "平台", "沙盒")),
            Map.entry("SERVICE", List.of("服务", "服务能力")),
            Map.entry("ROLE", List.of("岗位", "角色", "职责", "职位")),
            Map.entry("ORGANIZATION", List.of("公司", "企业", "集团", "机构", "组织", "团队",
                    "联合体", "委员会", "协会", "研究院", "大学", "银行"))
    );

    private GraphEntityTypeResolver() {
    }

    static String resolve(String name, String modelType, String description) {
        return resolve(name, List.of(modelType), description);
    }

    static String resolve(String name, List<String> modelTypes, String description) {
        List<String> normalizedCandidates = new ArrayList<>();
        for (String modelType : modelTypes) {
            String normalized = normalizeType(modelType);
            if (!UNKNOWN_TYPE.equals(normalized)) {
                normalizedCandidates.add(normalized);
            }
        }
        String fallback = mostFrequentCandidate(normalizedCandidates);
        String evidenceType = inferFromEntityItself(name, description);
        return evidenceType == null ? fallback : evidenceType;
    }

    /**
     * Uses only evidence that describes the entity itself. The previous resolver
     * scanned the complete description and therefore interpreted contextual words
     * such as “公司” as the entity type: “宋知遥是某公司的创始人” became an
     * ORGANIZATION and “某公司的研发中心位于鹤川材料走廊” made the location an
     * ORGANIZATION. A model type is now overridden only by a strong name suffix or
     * by an explicit self-description clause.
     */
    private static String inferFromEntityItself(String name, String description) {
        String normalizedName = StringUtils.defaultString(name).trim();
        if (EVENT_ACTION_PHRASE.matcher(normalizedName).matches()) {
            return "EVENT";
        }
        if (PRODUCT_MODEL_NAME.matcher(normalizedName).matches()) {
            return "PRODUCT";
        }
        String suffixType = typeFromMarkers(normalizedName, NAME_SUFFIX_MARKERS, true);
        if (suffixType != null) {
            return suffixType;
        }

        String normalizedDescription = StringUtils.defaultString(description).trim();
        if (normalizedDescription.contains("公司的创始人")
                || normalizedDescription.contains("公司创始人")) {
            return "PERSON";
        }
        if (normalizedDescription.endsWith("所在地")) {
            return "LOCATION";
        }

        String selfDescription = selfDescriptionClause(normalizedName, description);
        if (StringUtils.isBlank(selfDescription)) {
            return null;
        }
        // A person or product complement is more specific than a contextual
        // organization word in phrases such as “是该公司发布的巡检产品”.
        for (String type : List.of("PERSON", "PRODUCT", "LOCATION", "DOCUMENT",
                "PROCESS", "EVENT", "SYSTEM", "SERVICE", "ROLE", "ORGANIZATION")) {
            if (containsAny(selfDescription, SELF_DESCRIPTION_MARKERS.get(type))) {
                return type;
            }
        }
        return null;
    }

    private static String selfDescriptionClause(String name, String description) {
        String value = StringUtils.defaultString(description).trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("一家") || value.startsWith("一所") || value.startsWith("一个")
                || value.startsWith("一名") || value.startsWith("一种")) {
            return firstClause(value);
        }
        if (StringUtils.isBlank(name)) {
            return "";
        }
        for (String copula : List.of("是一家", "是一所", "是一个", "是一名", "是一种",
                "是", "为", "属于", "系")) {
            int start = value.indexOf(name + copula);
            if (start >= 0) {
                return firstClause(value.substring(start + name.length()));
            }
        }
        return "";
    }

    private static String firstClause(String value) {
        int end = value.length();
        for (char delimiter : new char[]{'。', '；', ';', '\n', '\r'}) {
            int index = value.indexOf(delimiter);
            if (index >= 0) {
                end = Math.min(end, index);
            }
        }
        return value.substring(0, Math.min(end, 80));
    }

    private static String typeFromMarkers(String value, Map<String, List<String>> markers,
                                          boolean suffixOnly) {
        String normalized = StringUtils.defaultString(value).toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> entry : markers.entrySet()) {
            for (String marker : entry.getValue()) {
                String normalizedMarker = marker.toLowerCase(Locale.ROOT);
                if (suffixOnly ? normalized.endsWith(normalizedMarker)
                        : normalized.contains(normalizedMarker)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private static boolean containsAny(String value, List<String> markers) {
        if (markers == null) {
            return false;
        }
        return typeFromMarkers(value, Map.of("MATCH", markers), false) != null;
    }

    private static String mostFrequentCandidate(List<String> candidates) {
        if (candidates.isEmpty()) {
            return UNKNOWN_TYPE;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String candidate : candidates) {
            counts.merge(candidate, 1, Integer::sum);
        }
        String winner = candidates.get(0);
        int winningCount = counts.get(winner);
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > winningCount) {
                winner = entry.getKey();
                winningCount = entry.getValue();
            }
        }
        return winner;
    }

    static String normalizeName(String value) {
        return StringUtils.defaultString(value).trim().replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }

    private static String normalizeType(String value) {
        String normalized = StringUtils.defaultString(value).trim().toUpperCase(Locale.ROOT);
        return ALLOWED_TYPES.contains(normalized) ? normalized : UNKNOWN_TYPE;
    }
}
