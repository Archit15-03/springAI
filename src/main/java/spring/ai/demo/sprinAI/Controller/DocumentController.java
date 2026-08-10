package spring.ai.demo.sprinAI.Controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import spring.ai.demo.sprinAI.Service.DocumentIngestionService;

import java.io.IOException;
import java.util.List;
import java.util.Map;


@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:4200")
public class DocumentController {

    private final DocumentIngestionService ingestionService;
    private final JdbcTemplate jdbcTemplate;

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @RequestParam("file") MultipartFile file) {

        // Basic validation
        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No file provided"));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Only PDF files are supported"));
        }

        try {
            int chunksStored = ingestionService.ingestDocument(file, filename);

            return ResponseEntity.ok(Map.of(
                    "message",       "Document ingested successfully",
                    "filename",      filename,
                    "chunksStored",  chunksStored
            ));

        } catch (IOException e) {
            log.error("Failed to ingest document: {}", filename, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to process document: " + e.getMessage()));
        }
    }

    // Health check — useful to verify the vector store connection is live
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok", "layer", "ingestion"));
    }

    @GetMapping("/list")
    public ResponseEntity<?> listDocuments() {
        try {
            List<Map<String, Object>> result = jdbcTemplate.query(
                    """
                    SELECT metadata->>'source_file' as filename,
                           COUNT(*) as chunks
                    FROM vector_store
                    WHERE metadata->>'content_type' IS NULL
                       OR metadata->>'content_type' = 'document'
                    GROUP BY metadata->>'source_file'
                    ORDER BY filename
                    """,
                    (rs, row) -> Map.of(
                            "filename", rs.getString("filename"),
                            "chunks",   rs.getInt("chunks")
                    )
            );
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to list documents", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to list documents: " + e.getMessage()));
        }
    }
}
