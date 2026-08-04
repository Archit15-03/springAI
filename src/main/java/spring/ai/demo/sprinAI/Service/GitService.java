package spring.ai.demo.sprinAI.Service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;


@Slf4j
@Service
public class GitService {

    @Value("${repo.ingestion.temp-dir:#{systemProperties['java.io.tmpdir']}/javadocs-repos}")
    private String tempDir;

    private final RestClient restClient = RestClient.create();


    public String getLatestCommitHash(String repoUrl, String branch) {
        try {

            String path = repoUrl
                    .replace("https://github.com/", "")
                    .replace(".git", "")
                    .trim();

            String apiUrl = "https://api.github.com/repos/" + path + "/commits/" + branch;

            log.info("Checking latest commit hash from: {}", apiUrl);

            Map response = restClient.get()
                    .uri(apiUrl)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "Spring-AI-App")
                    .retrieve()
                    .body(Map.class);

            if (response != null && response.containsKey("sha")) {
                return String.valueOf(response.get("sha")).substring(0, 40);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch commit hash from GitHub API: {}", e.getMessage());
            log.error("Stack trace: {}", e);
        }
        return null;
    }

    public Path cloneRepo(String repoUrl, String branch) throws GitAPIException, IOException {
        String repoName = extractRepoName(repoUrl);
        String dirName  = repoName + "-" + branch + "-" + System.currentTimeMillis();
        Path   cloneDir = Path.of(tempDir, dirName);
        Files.createDirectories(cloneDir);

        log.info("Cloning {} branch {} into {}", repoUrl, branch, cloneDir);

        Git.cloneRepository()
                .setURI(repoUrl)
                .setBranch("refs/heads/" + branch)
                .setDirectory(cloneDir.toFile())
                .setDepth(1)
                .setNoCheckout(false)
                .call()
                .close();

        log.info("Clone complete: {}", cloneDir);
        return cloneDir;
    }


    public void deleteClonedRepo(Path cloneDir) {
        if (cloneDir == null || !Files.exists(cloneDir)) return;

        try {
            Files.walk(cloneDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);

            log.info("Deleted cloned repo: {}", cloneDir);
        } catch (IOException e) {
            log.warn("Failed to delete cloned repo {}: {}", cloneDir, e.getMessage());
        }
    }

    private String extractRepoName(String repoUrl) {
        String[] parts = repoUrl.replace(".git", "").split("/");
        return parts[parts.length - 1];
    }
}
