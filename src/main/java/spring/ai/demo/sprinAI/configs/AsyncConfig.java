package spring.ai.demo.sprinAI.configs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;


@EnableAsync
@Configuration
public class AsyncConfig {

    @Value("${repo.ingestion.async-thread-pool-size:4}")
    private int poolSize;

    @Bean(name = "repoIngestionExecutor")
    public Executor repoIngestionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize * 2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("repo-ingestion-");
        executor.initialize();
        return executor;
    }
}
