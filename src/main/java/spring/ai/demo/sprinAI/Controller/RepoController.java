package spring.ai.demo.sprinAI.Controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import spring.ai.demo.sprinAI.Models.RepoIngestRequest;
import spring.ai.demo.sprinAI.Entity.RepoJob;
import spring.ai.demo.sprinAI.Repository.RepoJobRepository;
import spring.ai.demo.sprinAI.Service.AsyncService;
import spring.ai.demo.sprinAI.Service.GraphRagService;
import spring.ai.demo.sprinAI.Service.RepoIngestionService;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;


@Slf4j
@RestController
@RequestMapping("/api/repo")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:4200")
public class RepoController {

    private final RepoIngestionService repoIngestionService;
    private final RepoJobRepository    repoJobRepository;
    private final GraphRagService      graphRagService;

    @Autowired
    private AsyncService asyncService;

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@RequestBody RepoIngestRequest request) {
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "repoUrl is required"));
        }

        if (!request.repoUrl().startsWith("https://github.com/")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Only public GitHub repos are supported (https://github.com/...)"));
        }

        try {
            RepoJob job = repoIngestionService.startIngestion(
                    request.repoUrl(), request.effectiveBranch());

            return ResponseEntity.accepted().body(Map.of(
                    "jobId",    job.getId().toString(),
                    "repoUrl",  job.getRepoUrl(),
                    "branch",   job.getBranch(),
                    "status",   job.getStatus().toString(),
                    "message",  "Ingestion started. Poll /api/repo/status/" + job.getId() + " for progress."
            ));

        } catch (Exception e) {
            log.error("Failed to start ingestion for {}: {}", request.repoUrl(), e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to start ingestion: " + e.getMessage()));
        }
    }


    @GetMapping("/status/{jobId}")
    public ResponseEntity<?> status(@PathVariable UUID jobId) {
        Optional<RepoJob> jobResponse = repoJobRepository.findById(jobId);
//        if(jobResponse.isPresent() &&
//                jobResponse.get().getStatus().equals(RepoJob.JobStatus.PENDING)) asyncService.processAsync(jobId);
        return jobResponse
                .map(job -> ResponseEntity.ok(Map.of(
                        "jobId",          job.getId().toString(),
                        "repoUrl",        job.getRepoUrl(),
                        "branch",         job.getBranch(),
                        "status",         job.getStatus().toString(),
                        "totalFiles",     job.getTotalFiles(),
                        "processedFiles", job.getProcessedFiles(),
                        "lastCommitHash", job.getLastCommitHash() != null ? job.getLastCommitHash() : "",
                        "updatedAt",      job.getUpdatedAt().toString(),
                        "createdAt",      job.getCreatedAt().toString(),
                        "errorMessage",   job.getErrorMessage() != null ? job.getErrorMessage() : ""
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/query")
    public ResponseEntity<?> query(@RequestBody Map<String, String> body) {
        String question = body.get("question");
        String repoUrl  = body.get("repoUrl");
        String branch   = body.getOrDefault("branch", "main");

        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }
        if (repoUrl == null || repoUrl.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "repoUrl is required"));
        }

        // Verify repo has been indexed
        boolean indexed = repoJobRepository.findByRepoUrlAndBranch(repoUrl, branch)
                .map(job -> job.getStatus() == RepoJob.JobStatus.DONE)
                .orElse(false);

        if (!indexed) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Repository not indexed yet. POST /api/repo/ingest first and wait for status DONE."
            ));
        }

        try {
            GraphRagService.GraphRagResponse response =
                    graphRagService.queryRepo(question, repoUrl, branch);

            return ResponseEntity.ok(Map.of(
                    "answer",         response.answer(),
                    "vectorSources",  response.vectorSources(),
                    "graphSources",   response.graphSources(),
                    "traversalPath",  response.traversalPath()
            ));

        } catch (Exception e) {
            log.error("GraphRAG query failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Query failed: " + e.getMessage()));
        }
    }


    @GetMapping("/list")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(
                repoJobRepository.findAll().stream()
                        .map(job -> Map.of(
                                "jobId",   job.getId().toString(),
                                "repoUrl", job.getRepoUrl(),
                                "branch",  job.getBranch(),
                                "status",  job.getStatus().toString(),
                                "files",   job.getTotalFiles()
                        ))
                        .toList()
        );
    }

    /**
     * DELETE /api/repo/{jobId}
     * Deletes everything for this repo — code_graph nodes + vector_store chunks + job entry.
     */
    @DeleteMapping("/{jobId}")
    public ResponseEntity<?> deleteRepo(@PathVariable UUID jobId) {
        return repoIngestionService.deleteRepo(jobId);
    }

}
