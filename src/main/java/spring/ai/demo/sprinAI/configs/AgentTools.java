package spring.ai.demo.sprinAI.configs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AgentTools {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    public AgentTools(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    /*
     * The LLM calls this when the user asks a specific question
     */
    @Tool(description = """
            Search through uploaded documents to find relevant information
            and answer a specific question. Use this when the user asks
            a question that requires looking up information from documents.
            Returns the most relevant text chunks with their source filenames.
            """)
    public String searchDocuments(String question) {
        log.info("[AGENT TOOL] searchDocuments called with: {}", question);

        var searchRequest = SearchRequest.builder()
                .query(question)
                .topK(4)
                .similarityThreshold(0.1)
                .build();

        List<Document> results = vectorStore.similaritySearch(searchRequest);

        if (results.isEmpty()) {
            return "No relevant information found in the uploaded documents for: " + question;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" relevant sections:\n\n");
        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            String source = String.valueOf(doc.getMetadata().getOrDefault("source_file", "unknown"));
            sb.append("[").append(i + 1).append("] Source: ").append(source).append("\n");
            sb.append(doc.getText()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * The LLM calls this when the user asks what documents are available
     */
    @Tool(description = """
            List all documents that have been uploaded and are available
            in the knowledge base. Use this when the user asks what documents
            are available, what files have been uploaded, or wants to know
            what the system knows about.
            Returns a list of filenames and their chunk counts.
            """)
    public String listUploadedDocuments(@ToolParam(description = "Pass an empty string. This parameter is not used for filtering.") String filter) {
        log.info("[AGENT TOOL] listUploadedDocuments called");

        try {
            List<String> rows = jdbcTemplate.query(
                    "SELECT metadata->>'source_file' as filename, COUNT(*) as chunks " +
                            "FROM vector_store " +
                            "GROUP BY metadata->>'source_file' " +
                            "ORDER BY filename",
                    (rs, rowNum) -> rs.getString("filename") +
                            " (" + rs.getInt("chunks") + " chunks)"
            );

            if (rows.isEmpty()) {
                return "No documents have been uploaded yet.";
            }

            return "Uploaded documents:\n" + rows.stream()
                    .map(r -> "  - " + r)
                    .collect(Collectors.joining("\n"));

        } catch (Exception e) {
            log.error("Failed to list documents", e);
            return "Failed to retrieve document list: " + e.getMessage();
        }
    }

    /**
     * The LLM calls this when the user explicitly asks for a summary
     */
    @Tool(description = """
            Summarize the content of a specific document by its filename.
            Use this when the user explicitly asks for a summary of a document,
            or wants to know what a specific file is about. The description should be not more than 100 words.
            Read all the chunks of the given file and then give a summary.
            Provide the exact filename as it was uploaded (e.g. 'report.pdf').
            """)
    public String summarizeDocument(String filename) {
        log.info("[AGENT TOOL] summarizeDocument called for: {}", filename);

        // Retrieve all chunks for this document (higher topK, no threshold)
        var searchRequest = SearchRequest.builder()
                .query("main topics key points overview summary")
                .topK(10)
                .similarityThreshold(0.0)
                .filterExpression("source_file == '" + filename + "'")
                .build();

        List<Document> chunks = vectorStore.similaritySearch(searchRequest);

        if (chunks.isEmpty()) {
            return "No content found for document: " + filename +
                    ". Make sure the filename matches exactly as uploaded.";
        }

        // Combine all chunks into a single context block for the LLM to summarize
        String fullContent = chunks.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        return "Content from " + filename + " (" + chunks.size() + " sections):\n\n" + fullContent;
    }
}
