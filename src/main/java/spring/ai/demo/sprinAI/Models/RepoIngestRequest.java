package spring.ai.demo.sprinAI.Models;

import spring.ai.demo.sprinAI.Entity.RepoJob;

import java.time.LocalDateTime;
import java.util.UUID;


public record RepoIngestRequest(
        String repoUrl,
        String branch    // optional — defaults to "main" if null/blank
) {
    public String effectiveBranch() {
        return (branch == null || branch.isBlank()) ? "main" : branch;
    }
}


record RepoIngestResponse(
        UUID jobId,
        String repoUrl,
        String branch,
        RepoJob.JobStatus status,
        String message,
        Integer totalFiles,
        Integer processedFiles,
        String lastCommitHash,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String errorMessage
) {
    static RepoIngestResponse from(RepoJob job, String message) {
        return new RepoIngestResponse(
                job.getId(),
                job.getRepoUrl(),
                job.getBranch(),
                job.getStatus(),
                message,
                job.getTotalFiles(),
                job.getProcessedFiles(),
                job.getLastCommitHash(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getErrorMessage()
        );
    }
}
