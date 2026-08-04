package spring.ai.demo.sprinAI.Entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;


@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "repo_jobs",
        uniqueConstraints = @UniqueConstraint(columnNames = {"repo_url", "branch"}))
public class RepoJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "repo_url", nullable = false, length = 1000)
    private String repoUrl;

    @Column(name = "branch", nullable = false, length = 100)
    @Builder.Default
    private String branch = "main";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private JobStatus status = JobStatus.PENDING;

    @Column(name = "last_commit_hash", length = 40)
    private String lastCommitHash;

    @Column(name = "total_files")
    @Builder.Default
    private Integer totalFiles = 0;

    @Column(name = "processed_files")
    @Builder.Default
    private Integer processedFiles = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    public enum JobStatus {
        PENDING,
        CLONING,
        PARSING,
        EMBEDDING,
        DONE,
        SYNCING,
        FAILED
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
