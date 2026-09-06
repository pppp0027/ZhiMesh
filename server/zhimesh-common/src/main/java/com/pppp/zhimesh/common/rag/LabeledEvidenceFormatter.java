package com.pppp.zhimesh.common.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.rag.content.Content;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders selected retrieval evidence with type labels so the answer model can
 * tell original document chunks apart from inferred graph relations. Shared by
 * the streaming content injector and the blocking prompt assembly.
 */
public final class LabeledEvidenceFormatter {

    static final String LABEL_DOCUMENT_SEGMENT = "【文档片段】";
    static final String LABEL_GRAPH_RELATION = "【图谱关系·推断】";
    static final String LABEL_GRAPH_RELATION_GENERALIZED = "【图谱关系·泛化】";

    private LabeledEvidenceFormatter() {
    }

    /** Plain newline-joined rendering used when labeling is switched off. */
    public static String format(boolean labelingEnabled, List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return StringUtils.EMPTY;
        }
        if (!labelingEnabled) {
            return contents.stream()
                    .map(content -> content.textSegment().text())
                    .collect(Collectors.joining("\n"));
        }
        return format(contents);
    }

    public static String format(List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return StringUtils.EMPTY;
        }
        StringBuilder rendered = new StringBuilder();
        for (int index = 0; index < contents.size(); index++) {
            Content content = contents.get(index);
            if (index > 0) {
                rendered.append("\n\n");
            }
            rendered.append('[').append(index + 1).append(']')
                    .append(label(content.textSegment().metadata()))
                    .append('\n')
                    .append(content.textSegment().text());
        }
        return rendered.toString();
    }

    static String label(Metadata metadata) {
        if (!RetrievedCandidate.GRAPH_RELATION.equals(
                metadata.getString(RetrievedCandidate.CONTENT_TYPE))) {
            return LABEL_DOCUMENT_SEGMENT;
        }
        // A relation shared by many documents is a graph hint without precise
        // document-level provenance; the model should weight it accordingly.
        return "true".equals(metadata.getString(
                GraphStoreContentRetriever.GRAPH_PROVENANCE_AMBIGUOUS))
                ? LABEL_GRAPH_RELATION_GENERALIZED
                : LABEL_GRAPH_RELATION;
    }
}
