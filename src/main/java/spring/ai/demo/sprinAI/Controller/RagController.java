package spring.ai.demo.sprinAI.Controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import spring.ai.demo.sprinAI.Service.RagService;
import spring.ai.demo.sprinAI.Models.AskRequest;
import spring.ai.demo.sprinAI.Models.AskResponse;

/**
 * Layer 2 — Question answering endpoint
 *
 * POST /api/rag/ask
 *   { "question": "What is the payment amount?" }
 *   →
 *   { "answer": "...", "sources": [ { "sourceFile": "...", "snippet": "...", "score": 0.91 } ] }
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:3000") // React dev server
public class RagController {

    private final RagService ragService;

    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody AskRequest request) {

        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().body("Question must not be empty");
        }

        try {
            AskResponse response = ragService.askQuestion(request.question(), request.documentName());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to answer question: {}", request.question(), e);
            return ResponseEntity.internalServerError()
                    .body("Failed to generate an answer: " + e.getMessage());
        }
    }
}