package com.pppp.zhimesh.common.rag.bm25;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.rag.QueryInformationAnalyzer;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Versioned, deterministic tokenizer for Chinese text and technical identifiers.
 * CJK runs produce overlapping bigrams and trigrams; Latin text additionally
 * preserves API paths, configuration keys, error codes and camel-case parts.
 */
@Component
public class Bm25Tokenizer {

    static final int MAX_TERM_CHARACTERS = 512;
    static final int MAX_ANALYZER_VERSION_CHARACTERS = 64;

    private static final Pattern CJK_RUN = Pattern.compile(
            "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]+");
    private static final Pattern IDENTIFIER = Pattern.compile(
            "(?U)/?[\\p{L}\\p{N}$][\\p{L}\\p{N}${}]*"
                    + "(?:[./:_\\\\-][\\p{L}\\p{N}${}]+)*");
    private static final Pattern CAMEL_ACRONYM_BOUNDARY = Pattern.compile("([A-Z]+)([A-Z][a-z])");
    private static final Pattern CAMEL_WORD_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Pattern LETTER_DIGIT_BOUNDARY = Pattern.compile(
            "(?<=[\\p{L}])(?=[\\p{N}])|(?<=[\\p{N}])(?=[\\p{L}])");
    private static final Pattern IDENTIFIER_SEPARATOR = Pattern.compile("[./:_\\\\${}\\-]+");

    private final String analyzerVersion;

    @Autowired
    public Bm25Tokenizer(ZhiMeshProperties properties) {
        this(properties.getRetrieval().getBm25().getAnalyzerVersion());
    }

    public Bm25Tokenizer(String analyzerVersion) {
        if (StringUtils.isBlank(analyzerVersion)) {
            throw new IllegalArgumentException("BM25 analyzerVersion must not be blank");
        }
        String normalizedVersion = analyzerVersion.trim();
        if (codePointLength(normalizedVersion) > MAX_ANALYZER_VERSION_CHARACTERS) {
            throw new IllegalArgumentException(
                    "BM25 analyzerVersion must not exceed "
                            + MAX_ANALYZER_VERSION_CHARACTERS + " characters");
        }
        this.analyzerVersion = normalizedVersion;
    }

    public String analyzerVersion() {
        return analyzerVersion;
    }

    public Bm25Analysis analyze(String input) {
        if (StringUtils.isBlank(input)) {
            return new Bm25Analysis(Map.of(), 0);
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC);
        LinkedHashMap<String, Integer> frequencies = new LinkedHashMap<>();
        int length = addCjkTerms(normalized, frequencies);
        length += addIdentifierTerms(normalized, frequencies);
        return new Bm25Analysis(frequencies, length);
    }

    /**
     * Query-only analysis. Index terms remain version-compatible while low-information
     * conversational phrases are removed before online search.
     */
    public Bm25Analysis analyzeQuery(String input) {
        if (StringUtils.isBlank(input)) return new Bm25Analysis(Map.of(), 0);
        String cleaned = QueryInformationAnalyzer.clean(input);
        Bm25Analysis analysis = analyze(cleaned);
        LinkedHashMap<String, Integer> informative = new LinkedHashMap<>();
        analysis.termFrequencies().forEach((term, frequency) -> {
            if (QueryInformationAnalyzer.isInformativeTerm(term)) {
                informative.put(term, frequency);
            }
        });
        int length = informative.values().stream().mapToInt(Integer::intValue).sum();
        return new Bm25Analysis(informative, length);
    }

    private static int addCjkTerms(String input, Map<String, Integer> frequencies) {
        int count = 0;
        Matcher matcher = CJK_RUN.matcher(input);
        while (matcher.find()) {
            int[] codePoints = matcher.group().codePoints().toArray();
            if (codePoints.length == 1) {
                add(frequencies, new String(codePoints, 0, 1));
                count++;
                continue;
            }
            for (int size : new int[]{2, 3}) {
                for (int start = 0; start + size <= codePoints.length; start++) {
                    add(frequencies, new String(codePoints, start, size));
                    count++;
                }
            }
        }
        return count;
    }

    private static int addIdentifierTerms(String input, Map<String, Integer> frequencies) {
        int count = 0;
        // Mask CJK runs before scanning identifiers. Without this boundary, a
        // contiguous value such as "知识getUserName" is consumed as one Unicode
        // identifier and the useful Latin identifier is lost when CJK is skipped.
        Matcher matcher = IDENTIFIER.matcher(CJK_RUN.matcher(input).replaceAll(" "));
        while (matcher.find()) {
            String raw = matcher.group();
            Set<String> occurrenceTerms = identifierTerms(raw);
            for (String term : occurrenceTerms) {
                add(frequencies, term);
                count++;
            }
        }
        return count;
    }

    private static Set<String> identifierTerms(String raw) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String canonical = raw.toLowerCase(Locale.ROOT);
        addIfNotBlank(terms, canonical);
        if (canonical.startsWith("/") && canonical.length() > 1) {
            addIfNotBlank(terms, canonical.substring(1));
        }

        String camelSeparated = CAMEL_ACRONYM_BOUNDARY.matcher(raw).replaceAll("$1 $2");
        camelSeparated = CAMEL_WORD_BOUNDARY.matcher(camelSeparated).replaceAll("$1 $2");
        for (String piece : IDENTIFIER_SEPARATOR.split(camelSeparated)) {
            for (String camelPiece : piece.split("\\s+")) {
                addIfNotBlank(terms, camelPiece.toLowerCase(Locale.ROOT));
                for (String alphaNumericPart : LETTER_DIGIT_BOUNDARY.split(camelPiece)) {
                    addIfNotBlank(terms, alphaNumericPart.toLowerCase(Locale.ROOT));
                }
            }
        }
        return terms;
    }

    private static void addIfNotBlank(Set<String> target, String value) {
        if (StringUtils.isNotBlank(value) && isPersistableTerm(value)) {
            target.add(value);
        }
    }

    private static void add(Map<String, Integer> frequencies, String term) {
        if (isPersistableTerm(term)) {
            frequencies.merge(term, 1, Integer::sum);
        }
    }

    private static boolean isPersistableTerm(String term) {
        return codePointLength(term) <= MAX_TERM_CHARACTERS;
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }
}
