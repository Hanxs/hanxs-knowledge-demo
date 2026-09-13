package com.example.knowledge.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseLoader {

    private final VectorStore vectorStore;
    private final DocumentTransformer textSplitter;

    @Value("classpath:doc/公司考勤制度.pdf")
    private org.springframework.core.io.Resource pdfResource;

    @Value("classpath:doc/knowledge.txt")
    private org.springframework.core.io.Resource txtResource;

    /**
     * 加载 PDF 文档到向量数据库
     */
    public void loadPdfToVectorStore() {
        DocumentReader pdfReader = new PagePdfDocumentReader(pdfResource);
        List<Document> documents = pdfReader.get();
        log.info("PDF抽取完成，共 {} 个页面", documents.size());

        List<Document> chunks = textSplitter.apply(documents);
        log.info("PDF切分完成，共 {} 个知识片段", chunks.size());

        vectorStore.write(chunks);
        log.info("PDF向量存储完成");
    }

    /**
     * 加载 TXT 文本到向量数据库
     */
    public void loadTxtToVectorStore() {
        DocumentReader txtReader = new TextReader(txtResource);
        List<Document> documents = txtReader.get();
        log.info("TXT抽取完成，共 {} 个文档", documents.size());

        List<Document> chunks = textSplitter.apply(documents);
        log.info("TXT切分完成，共 {} 个知识片段", chunks.size());

        vectorStore.write(chunks);
        log.info("TXT向量存储完成");
    }

    /**
     * 加载纯文本内容到向量数据库（动态传入）
     */
    public void loadTextToVectorStore(String textContent, Map<String, Object> metadata) {
        Document doc = new Document(textContent, metadata);
        List<Document> chunks = textSplitter.apply(List.of(doc));
        vectorStore.write(chunks);
        log.info("文本内容已加载，共 {} 个知识片段", chunks.size());
    }
}
