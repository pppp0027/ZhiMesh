package com.pppp.zhimesh.common.rag.intent;

import com.pppp.zhimesh.common.rag.QueryInformationAnalyzer;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative, explainable rules. Weak matches remain UNCERTAIN. */
@Component
public class RuleIntentRecognizer implements IntentRecognizer {
    private static final Pattern NO_RAG = Pattern.compile(
            "^(你好|您好|嗨|hello|hi|谢谢|感谢|thanks|thank you|再见|拜拜|好的|收到|你是谁|你能做什么|"
                    + "你(?:是|用的?是?|使用的?是?)(?:什么|哪个)模型|what model are you|which model are you|"
                    + "what (?:ai )?model do you use|早上好|晚上好|晚安|很高兴认识你)[!！,.，。?？~～]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RELATIONSHIP = Pattern.compile(
            "(什么关系|有何关系|之间的关系|如何关联|怎么关联|上下级|父子关系|依赖关系|依赖路径|调用关系|调用路径|调用链路?|关联关系|连接关系|影响关系|影响路径|关联路径|继承关系|外键关系|先后关系|状态流转|谁.*(认识|属于|依赖|连接).*谁|relationship between|how (is|are).*(related|connected)|dependency (between|path))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXACT_IDENTIFIER = Pattern.compile(
            "(\\b[A-Z][A-Za-z0-9_$]*(Service|Controller|Repository|Mapper|Exception|Error|Config|Factory)\\b|"
                    + "\\b[A-Z]{2,}[-_]?\\d{2,}\\b|\\b(GET|POST|PUT|DELETE|PATCH)\\s+/|"
                    + "(/[a-zA-Z0-9._{}-]+){2,}|\\b[a-zA-Z_][a-zA-Z0-9_]*\\([^)]*\\)|"
                    + "\\b[a-z][a-z0-9]*(?:[A-Z][A-Za-z0-9]*)+\\b|"
                    + "\\b[a-zA-Z][a-zA-Z0-9]*(?:_[a-zA-Z0-9]+)+\\b|"
                    + "\\b[a-zA-Z][a-zA-Z0-9_-]*(?:\\.[a-zA-Z0-9_-]+)+\\b|"
                    + "错误码|异常码|类名|接口名|配置项|字段名?|表名|参数名?|环境变量)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMBIGUOUS = Pattern.compile(
            "^(这个|那个|它|他们|她们|上面那个|继续|然后呢|为什么|怎么做|怎么办|再说说)[?？!！,.，。]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTEXT_REFERENCE = Pattern.compile(
            "^(?:(?:那|那么|然后|还有|另外|再)?(?:它|他|她|它们|他们|她们|其)(?:呢|又|也|为什么|怎么|如何|是否|会|能|要|是|有|没|不|把|被|的|[?？!！,.，。])|"
                    + "(?:上述|上面|前面|刚才|前者|后者)|"
                    + "(?:继续|接着|然后|再说说|展开说说|还有呢)|"
                    + "(?:那|那么)(?:为什么|怎么|如何|是否|怎么办|怎么做)|"
                    + "那.+呢)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EMBEDDED_CONTEXT_REFERENCE = Pattern.compile(
            "(?:这个|那个|这些|那些|它|它们|上述|上面|前面|刚才|前者|后者)"
                    + "(?:问题|接口|配置|方案|方法|步骤|错误|功能|模型|服务|工具|文档|知识库|节点|流程|情况|做法)?"
                    + "(?:呢|又|也|为什么|为何|怎么|如何|是否|能否|会不会|支持吗|可以吗|失败|报错)|"
                    + "\\b(?:this|that|it|they|the former|the latter|above|previous)\\b.{0,20}"
                    + "(?:why|how|whether|can|could|fail|error)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public IntentDecision recognize(IntentRoutingContext context) {
        String query = StringUtils.trimToEmpty(context.query());
        SignalAnalysis analysis = analyze(query);
        if (StringUtils.isBlank(query)) {
            return IntentDecision.uncertain(analysis.signals(), "rule", "blank query");
        }
        if (analysis.signals().contains(IntentSignal.NO_RAG_PATTERN)) {
            return new IntentDecision(QueryIntent.NO_RAG, 1D, 1D, analysis.signals(),
                    analysis.sourceHints(), "rule", "matched an exact conversational pattern");
        }
        if (analysis.signals().contains(IntentSignal.RELATION_QUERY)) {
            return new IntentDecision(QueryIntent.RELATIONSHIP, 0.99D, 0.99D, analysis.signals(),
                    analysis.sourceHints(), "rule", "matched a strong relationship expression");
        }
        return new IntentDecision(QueryIntent.UNCERTAIN, 0D, 0D, analysis.signals(),
                analysis.sourceHints(), "rule", analysis.signals().contains(IntentSignal.AMBIGUOUS_CONTEXT)
                ? "query requires conversational context" : "no decisive rule");
    }

    /** Strong conversational matches can bypass query embedding altogether. */
    public boolean isDefiniteNoRag(String rawQuery) {
        return analyze(rawQuery).signals().contains(IntentSignal.NO_RAG_PATTERN);
    }

    /**
     * Only context-dependent or genuinely low-information turns need an LLM
     * rewrite. Complete standalone questions stay on the zero-extra-call path.
     */
    public boolean requiresContextRewrite(String rawQuery) {
        SignalAnalysis analysis = analyze(rawQuery);
        return analysis.signals().contains(IntentSignal.AMBIGUOUS_CONTEXT)
                || !QueryInformationAnalyzer.hasInformativeTerms(rawQuery);
    }

    SignalAnalysis analyze(String rawQuery) {
        String query = StringUtils.trimToEmpty(rawQuery).toLowerCase(Locale.ROOT);
        EnumSet<IntentSignal> signals = EnumSet.noneOf(IntentSignal.class);
        EnumSet<KnowledgeSourceType> sourceHints = EnumSet.noneOf(KnowledgeSourceType.class);
        if (NO_RAG.matcher(query).find()) signals.add(IntentSignal.NO_RAG_PATTERN);
        if (RELATIONSHIP.matcher(query).find()) signals.add(IntentSignal.RELATION_QUERY);
        if (EXACT_IDENTIFIER.matcher(rawQuery == null ? "" : rawQuery).find()) {
            signals.add(IntentSignal.EXACT_IDENTIFIER);
        }
        if (AMBIGUOUS.matcher(query).find() || CONTEXT_REFERENCE.matcher(query).find()
                || EMBEDDED_CONTEXT_REFERENCE.matcher(query).find()) {
            signals.add(IntentSignal.AMBIGUOUS_CONTEXT);
        }
        return new SignalAnalysis(Set.copyOf(signals), Set.copyOf(sourceHints));
    }

    record SignalAnalysis(Set<IntentSignal> signals, Set<KnowledgeSourceType> sourceHints) {
    }
}
