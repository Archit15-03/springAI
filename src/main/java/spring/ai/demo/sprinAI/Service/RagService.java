package spring.ai.demo.sprinAI.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import spring.ai.demo.sprinAI.Models.AskResponse;

import java.util.List;

@Slf4j
@Service
public class RagService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    // topK            : how many chunks to retrieve
    private static final int TOP_K = 4;
    private static final double SIMILARITY_THRESHOLD = 0.1;

    public RagService(VectorStore vectorStore, ChatClient.Builder chatClientBuilder) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
    }

    public AskResponse askQuestion(String question, String documentName) {

        log.info("Received question: '{}' (scoped to document: {})",
                question, documentName != null ? documentName : "ALL");


        // similaritySearch embeds the question via Ollama, then finds the closest matching chunks in PGVector using cosine similarity.
        var searchRequestBuilder = SearchRequest.builder()
                .query(question)
                .topK(TOP_K)
                .similarityThreshold(SIMILARITY_THRESHOLD);

        boolean isScoped = documentName != null && !documentName.isBlank();
        if (isScoped) {
            searchRequestBuilder.filterExpression(
                    new FilterExpressionBuilder()
                            .eq("source_file", documentName)
                            .build()
            );
        }
        log.info("searchRequestBuilder {}",searchRequestBuilder);

//        var debugRequest = SearchRequest.builder()
//                .query(question)
//                .topK(4)
//                .similarityThreshold(0.0)
//                .build();
//        List<Document> debugChunks = vectorStore.similaritySearch(debugRequest);
//        log.info("DEBUG unfiltered search returned {} chunks", debugChunks.size());
//        debugChunks.forEach(d -> log.info("DEBUG chunk score={} source={}",
//                d.getScore(), d.getMetadata().get("source_file")));

        List<Document> retrievedChunks = vectorStore.similaritySearch(searchRequestBuilder.build());
        log.info("Retrieved {} relevant chunks", retrievedChunks.size());

        if (retrievedChunks.isEmpty()) {
            String message = isScoped
                    ? "I couldn't find any relevant information in '" + documentName + "' to answer that question. " +
                    "Double-check the filename matches exactly what you uploaded."
                    : "I couldn't find any relevant information in the uploaded documents to answer that question.";
            return new AskResponse(message, List.of());
        }

        // Build the grounded context block from retrieved chunks
        String context = buildContextBlock(retrievedChunks);

        // Construct the prompt
        String systemInstructions = """
                You are a helpful assistant that answers questions based ONLY on the provided context.
                Rules:
                1. Only use information from the context below to answer.
                2. If the context doesn't contain enough information to answer, say so clearly.
                3. Do not use any outside knowledge or make assumptions beyond the context.
                4. Be concise and direct.

                Context:
                %s
                """.formatted(context);

        //llm call
        String answer = chatClient.prompt()
                .system(systemInstructions)
                .user(question)
                .call()
                .content();

        log.info("Generated answer ({} chars)", answer != null ? answer.length() : 0);

        //Build the sources list
        List<AskResponse.SourceChunk> sources = retrievedChunks.stream()
                .map(this::toSourceChunk)
                .toList();

        return new AskResponse(answer, sources);
    }

    private String buildContextBlock(List<Document> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            String sourceFile = String.valueOf(chunk.getMetadata().getOrDefault("source_file", "unknown"));
            sb.append("[Source ").append(i + 1).append(" - ").append(sourceFile).append("]\n");
            sb.append(chunk.getText()).append("\n\n");
        }
        return sb.toString();
    }

    private AskResponse.SourceChunk toSourceChunk(Document chunk) {
        String sourceFile = String.valueOf(chunk.getMetadata().getOrDefault("source_file", "unknown"));

        // Trim long chunks down to a readable snippet
        String fullText = chunk.getText() != null ? chunk.getText() : "";
        String snippet = fullText.length() > 200 ? fullText.substring(0, 200) + "..." : fullText;

        //similarity score
        Double score = chunk.getScore();

        return new AskResponse.SourceChunk(sourceFile, snippet, score);
    }
}