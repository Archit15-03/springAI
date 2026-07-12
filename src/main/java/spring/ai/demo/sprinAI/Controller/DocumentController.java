package spring.ai.demo.sprinAI.Controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import spring.ai.demo.sprinAI.Service.DocumentIngestionService;

import java.io.IOException;
import java.util.Map;

/**
 * Layer 1 — Upload endpoint
 *
 * POST /api/documents/upload
 *   multipart/form-data  →  { file: <pdf> }
 *   returns              →  { message, filename, chunksStored }
 */
@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
//@CrossOrigin(origins = "http://localhost:3000") // React dev server
public class DocumentController {

    private final DocumentIngestionService ingestionService;

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
}
