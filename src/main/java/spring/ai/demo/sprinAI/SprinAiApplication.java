package spring.ai.demo.sprinAI;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration;

@SpringBootApplication(exclude = {
		OpenAiEmbeddingAutoConfiguration.class,  // we use Ollama for embeddings, not OpenAI
		OllamaChatAutoConfiguration.class         // we use Groq (OpenAI-compatible) for chat, not Ollama
})
public class SprinAiApplication {
	public static void main(String[] args) {
		SpringApplication.run(SprinAiApplication.class, args);
	}
}