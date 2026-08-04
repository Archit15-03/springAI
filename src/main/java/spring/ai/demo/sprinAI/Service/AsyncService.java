package spring.ai.demo.sprinAI.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.util.UUID;


@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncService {

    private final RepoIngestionService repoIngestionService;

    @Async("repoIngestionExecutor")
    public void processAsync(UUID jobId) {
        repoIngestionService.processRepoAsync(jobId);
    }
}

