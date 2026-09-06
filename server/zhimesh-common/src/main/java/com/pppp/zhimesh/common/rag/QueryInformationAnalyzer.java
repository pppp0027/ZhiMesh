package com.pppp.zhimesh.common.rag;

import org.apache.commons.lang3.StringUtils;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic query cleanup shared by preflight routing, BM25 and relevance
 * gates. It performs no I/O and no model call.
 */
public final class QueryInformationAnalyzer {

    private static final List<String> LOW_INFORMATION_PHRASES = List.of(
            "能不能告诉我", "可以告诉我", "麻烦告诉我", "我想了解一下", "麻烦问一下",
            "请介绍一下", "请说明一下", "帮我查一下", "请告诉我", "我想知道",
            "请问一下", "介绍一下", "说明一下", "帮我看看", "麻烦问下",
            "什么时候", "有哪一些", "里面的", "里边的", "其中的", "哪一个",
            "为什么", "怎么办", "怎么样", "怎么做", "告诉一下", "讲一下", "说一下",
            "什么是", "是什么", "能否", "是否", "有没有", "在哪里", "谁是", "是谁",
            "如何", "怎么", "为何", "何时", "哪里", "哪儿", "多少", "几个",
            "哪个", "哪些", "关于", "对于", "这个", "那个", "这里", "那里",
            "里面", "里边", "其中", "可以", "相关", "进行", "一下", "有何", "请问", "请帮我",
            "请", "吗", "呢", "呀", "啊", "吧");
    private static final Pattern LOW_INFORMATION_PATTERN = Pattern.compile(
            LOW_INFORMATION_PHRASES.stream()
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .map(Pattern::quote)
                    .reduce((left, right) -> left + "|" + right)
                    .orElse("(?!)"), Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Set<String> CJK_STOP_TERMS = Set.of(
            "是谁", "谁是", "什么", "怎么", "如何", "为何", "哪个", "哪些", "是否",
            "哪里", "哪儿", "何时", "多少", "几个", "有没有", "里面", "里边", "其中",
            "关于", "对于", "这个", "那个", "这里", "那里", "可以", "一下", "有何",
            "请问", "相关", "进行", "告诉", "介绍", "说明");
    private static final Set<String> LATIN_STOP_WORDS = Set.of(
            "the", "and", "for", "with", "what", "who", "why", "how", "which", "is", "are",
            "was", "were", "this", "that", "from", "about", "please", "tell", "me", "can",
            "could", "would", "should", "does", "do", "a", "an", "of", "to", "in", "on");

    private QueryInformationAnalyzer() {
    }

    /** Removes conversational scaffolding before query-only tokenization. */
    public static String clean(String rawQuery) {
        if (StringUtils.isBlank(rawQuery)) return "";
        String normalized = Normalizer.normalize(rawQuery, Normalizer.Form.NFKC);
        String cleaned = LOW_INFORMATION_PATTERN.matcher(normalized).replaceAll(" ");
        return WHITESPACE.matcher(cleaned).replaceAll(" ").trim();
    }

    /** Post-tokenization guard for missed variants and generated n-grams. */
    public static boolean isInformativeTerm(String rawTerm) {
        String term = normalize(rawTerm);
        if (StringUtils.isBlank(term) || LATIN_STOP_WORDS.contains(term)
                || CJK_STOP_TERMS.contains(term)) {
            return false;
        }
        if (term.codePoints().allMatch(QueryInformationAnalyzer::isCjk)) {
            return term.codePointCount(0, term.length()) >= 2;
        }
        return term.codePoints().anyMatch(Character::isDigit) || term.length() >= 2;
    }

    /** Cheap lexical features used by request preflight and candidate gates. */
    public static Set<String> informativeTerms(String rawQuery) {
        String query = normalize(clean(rawQuery));
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String cjkRun : query.split("[^\\p{IsHan}]+")) {
            int[] points = cjkRun.codePoints().toArray();
            for (int size : new int[]{2, 3}) {
                for (int index = 0; index + size <= points.length; index++) {
                    String term = new String(points, index, size);
                    if (isInformativeTerm(term)) terms.add(term);
                }
            }
        }
        Arrays.stream(query.split("[^\\p{L}\\p{N}_./:+#-]+"))
                .map(String::trim)
                .filter(QueryInformationAnalyzer::isInformativeTerm)
                .forEach(terms::add);
        return Set.copyOf(terms);
    }

    public static boolean hasInformativeTerms(String rawQuery) {
        return !informativeTerms(rawQuery).isEmpty();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(StringUtils.defaultString(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).trim();
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
