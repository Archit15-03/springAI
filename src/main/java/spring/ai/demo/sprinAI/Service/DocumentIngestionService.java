package spring.ai.demo.sprinAI.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private final VectorStore vectorStore;

    //   defaultChunkSize  : 800 tokens  ≈ ~600 words per chunk
    //                       Large enough to keep context, small enough for precise retrieval
    //   minChunkSizeChars : 350 chars   — discard tiny leftover fragments (page headers etc.)
    private static final int DEFAULT_CHUNK_SIZE   = 800;
    private static final int MIN_CHUNK_SIZE_CHARS = 350;

    /**
     * Ingests a PDF file into the vector store.
     *
     * @param file     the uploaded PDF
     * @param filename original filename — stored as metadata so the frontend
     *                 can show "answer sourced from: contract.pdf, page 4"
     * @return number of chunks stored
     */
    public int ingestDocument(MultipartFile file, String filename) throws IOException {

        log.info("Starting ingestion for file: {} ({} bytes)", filename, file.getSize());

        // read PDF
        var pdfResource = new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        var readerConfig = PdfDocumentReaderConfig.builder()
                .withPagesPerDocument(1)
                .build();

        var pdfReader   = new PagePdfDocumentReader(pdfResource, readerConfig);
        List<Document> pages = pdfReader.get();
        log.info("Read {} pages from {}", pages.size(), filename);


        // Add the original filename to every document's metadata.
        pages.forEach(doc -> doc.getMetadata().putAll(Map.of(
                "source_file", filename,
                "ingestion_time", String.valueOf(System.currentTimeMillis())
        )));

        // Step 3 — Split into chunks
        var splitter = TokenTextSplitter.builder()
                .withChunkSize(DEFAULT_CHUNK_SIZE)
                .withMinChunkSizeChars(MIN_CHUNK_SIZE_CHARS)
                .withMinChunkLengthToEmbed(5)        // discard near-empty fragments
                .withMaxNumChunks(10_000)             // safety cap on total chunks per document
                .withKeepSeparator(true)              // preserves sentence boundaries
                .build();

        List<Document> chunks = splitter.apply(pages);
        log.debug("Split into {} chunks", chunks.size());

        // Embed + Store
        vectorStore.add(chunks);
        log.info("Successfully ingested {} chunks from {}", chunks.size(), filename);

        return chunks.size();

    }
}
