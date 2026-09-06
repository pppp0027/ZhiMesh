package com.pppp.zhimesh.common.interfaces;

import com.pppp.zhimesh.common.vo.EmbeddingIngestParam;
import com.pppp.zhimesh.common.vo.RetrieverCreateParam;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.rag.content.retriever.ContentRetriever;

public interface IRAGService {
    void ingest(Document document, EmbeddingIngestParam params);

    ContentRetriever createRetriever(RetrieverCreateParam param);
}
