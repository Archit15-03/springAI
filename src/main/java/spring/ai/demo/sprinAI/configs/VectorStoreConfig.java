package spring.ai.demo.sprinAI.configs;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Explicitly wires the Ollama embedding model to PGVector.
 *
 * Without this, Spring sees TWO EmbeddingModel beans on the classpath
 * (ollamaEmbeddingModel + openAiEmbeddingModel) and can't decide which
 * one to inject into PgVectorStoreAutoConfiguration — hence the startup error.
 *
 * We want:
 *   embeddings  → Ollama (nomic-embed-text, local, free)
 *   chat        → Groq   (llama-3.1-8b-instant, fast, free tier)
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    public VectorStore vectorStore(
            @Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel,
            JdbcTemplate jdbcTemplate) {

        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .dimensions(768)           // must match nomic-embed-text output
                .initializeSchema(true)
                .build();
    }
}
