package spring.ai.demo.sprinAI.Controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import spring.ai.demo.sprinAI.Service.AgentService;

import java.util.Map;


@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:3000")
public class AgentController {

    private final AgentService agentService;

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody Map<String, String> body) {

        String message = body.get("message");

        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "message must not be empty"));
        }

        try {
            String response = agentService.chat(message);
            return ResponseEntity.ok(Map.of("response", response));

        } catch (Exception e) {
            log.error("[AGENT] Failed to process message: {}", message, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Agent failed: " + e.getMessage()));
        }
    }
}
