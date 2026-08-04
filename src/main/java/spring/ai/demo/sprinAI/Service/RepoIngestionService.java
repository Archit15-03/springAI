package spring.ai.demo.sprinAI.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import spring.ai.demo.sprinAI.Entity.CodeGraphNode;
import spring.ai.demo.sprinAI.Entity.RepoJob;
import spring.ai.demo.sprinAI.Entity.RepoJob.JobStatus;
import spring.ai.demo.sprinAI.Repository.CodeGraphRepository;
import spring.ai.demo.sprinAI.Repository.RepoJobRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;


@Slf4j
@Service
@RequiredArgsConstructor
public class RepoIngestionService {

    private final GitService           gitService;
    private final TreeSitterService    treeSitterService;
    private final CodeGraphRepository  codeGraphRepository;
    private final RepoJobRepository    repoJobRepository;
    private final VectorStore          vectorStore;

    @Autowired
    private AsyncService asyncService;

    private static final int CHUNK_SIZE = 600;  // slightly smaller for code — more precise retrieval


    @Transactional
    public RepoJob startIngestion(String repoUrl, String branch) {
        Optional<RepoJob> existing = repoJobRepository.findByRepoUrlAndBranch(repoUrl, branch);

        if (existing.isPresent()) {
            RepoJob job = existing.get();
            // Don't re-trigger if already running
            if (job.getStatus() == JobStatus.CLONING ||
                    job.getStatus() == JobStatus.PARSING  ||
                    job.getStatus() == JobStatus.EMBEDDING) {
                log.info("Job already in progress for {} {}", repoUrl, branch);
                return job;
            }
            // Re-trigger sync check
            job.setStatus(JobStatus.PENDING);
            repoJobRepository.save(job);
            asyncService.processAsync(job.getId());
            return job;
        }

        // New repo — create job and start async
        RepoJob job = RepoJob.builder()
                .repoUrl(repoUrl)
                .branch(branch)
                .status(JobStatus.PENDING)
                .build();
        job = repoJobRepository.save(job);

        asyncService.processAsync(job.getId());
        return job;
    }


//    @Async("repoIngestionExecutor")
    public void processRepoAsync(UUID jobId) {
        RepoJob job = repoJobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));

        Path cloneDir = null;

        try {
            String repoUrl = job.getRepoUrl();
            String branch  = job.getBranch();

            String latestHash = gitService.getLatestCommitHash(repoUrl, branch);
            if (latestHash != null &&
                    latestHash.equals(job.getLastCommitHash()) &&
                    job.getStatus() == JobStatus.DONE) {

                log.info("Repo {}/{} already up to date at {}", repoUrl, branch, latestHash);
                return;
            }

            if (job.getLastCommitHash() != null) {
                log.info("Clearing old data for {}/{} before re-ingestion", repoUrl, branch);
                clearOldData(repoUrl, branch);
            }

            updateStatus(job, JobStatus.CLONING, null);
            cloneDir = gitService.cloneRepo(repoUrl, branch);

            updateStatus(job, JobStatus.PARSING, null);
            List<Path> codeFiles = findCodeFiles(cloneDir);
            job.setTotalFiles(codeFiles.size());
            repoJobRepository.save(job);
            log.info("Found {} Java files in {}/{}", codeFiles.size(), repoUrl, branch);

            List<CodeGraphNode> allNodes    = new ArrayList<>();
            List<Document>      allChunks   = new ArrayList<>();
            int processed = 0;

            for (Path javaFile : codeFiles) {
                try {
                    String content  = Files.readString(javaFile);
                    String filePath = cloneDir.relativize(javaFile).toString().replace("\\", "/");

                    List<CodeGraphNode> nodes = treeSitterService.parseCodeFile(
                            filePath, content, repoUrl, branch);
                    allNodes.addAll(nodes);

                    allChunks.addAll(chunkCodeFile(filePath, content, repoUrl, branch));

                    processed++;
                    if (processed % 10 == 0) {
                        job.setProcessedFiles(processed);
                        repoJobRepository.save(job);
                    }
                } catch (Exception e) {
                    log.warn("Skipping file {} due to error: {}", javaFile, e.getMessage());
                }
            }

            if (!allNodes.isEmpty()) {
                codeGraphRepository.saveAll(allNodes);
                log.info("Stored {} code graph nodes", allNodes.size());
            }

            updateStatus(job, JobStatus.EMBEDDING, null);
            if (!allChunks.isEmpty()) {
                vectorStore.add(allChunks);
                log.info("Stored {} code chunks in PGVector", allChunks.size());
            }

            job.setStatus(JobStatus.DONE);
            job.setLastCommitHash(latestHash);
            job.setTotalFiles(codeFiles.size());
            job.setProcessedFiles(processed);
            repoJobRepository.save(job);
            log.info("Ingestion complete for {}/{}", repoUrl, branch);

        } catch (Exception e) {
            log.error("Ingestion failed for job {}: {}", jobId, e.getMessage(), e);
            updateStatus(job, JobStatus.FAILED, e.getMessage());
        } finally {
            if (cloneDir != null) {
                gitService.deleteClonedRepo(cloneDir);
            }
        }
    }


    private List<Path> findCodeFiles(Path repoRoot) throws IOException {
        try (Stream<Path> stream = Files.walk(repoRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String path = p.toString();
                        return path.endsWith(".java") || path.endsWith(".js") ||
                                path.endsWith(".ts")   || path.endsWith(".py") ||
                                path.endsWith(".go")   || path.endsWith(".rs");
                    })
                    // Skip generated/test files to keep the index clean
                    .filter(p -> !p.toString().contains("target/"))
                    .filter(p -> !p.toString().contains("build/"))
                    .toList();
        }
    }


    private List<Document> chunkCodeFile(String filePath, String content,
                                         String repoUrl, String branch) {
        if (content.length() > 500_000) {
            log.warn("Skipping large file: {} ({} chars)", filePath, content.length());
            return List.of();
        }

        Document doc = new Document(content, Map.of(
                "source_file",  filePath,
                "repo_url",     repoUrl,
                "branch",       branch,
                "content_type", "code",
                "language",     "java"
        ));

        var splitter = TokenTextSplitter.builder()
                .withChunkSize(CHUNK_SIZE)
                .withMinChunkSizeChars(100)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(10_000)
                .withKeepSeparator(true)
                .build();

        return splitter.apply(List.of(doc));
    }

    private void clearOldData(String repoUrl, String branch) {
        codeGraphRepository.deleteByRepoUrlAndBranch(repoUrl, branch);
        log.info("Cleared old graph data for {}/{}", repoUrl, branch);
    }

    private void updateStatus(RepoJob job, JobStatus status, String errorMessage) {
        job.setStatus(status);
        job.setErrorMessage(errorMessage);
        repoJobRepository.save(job);
        log.info("Job {} status → {}", job.getId(), status);
    }
}
