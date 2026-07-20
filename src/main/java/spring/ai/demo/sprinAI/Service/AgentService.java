package spring.ai.demo.sprinAI.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import spring.ai.demo.sprinAI.configs.AgentTools;


@Slf4j
@Service
public class AgentService {

    private final ChatClient chatClient;
    private final AgentTools agentTools;

    public AgentService(ChatClient.Builder chatClientBuilder, AgentTools agentTools) {
        this.agentTools = agentTools;

        this.chatClient = chatClientBuilder
                .defaultTools(agentTools)
                .build();
    }

    public String chat(String userMessage) {
        log.info("[AGENT] Received message: {}", userMessage);

        String systemPrompt = """
                You are an intelligent document assistant with access to a knowledge base.
                You have tools to:
                  1. Search documents for specific information
                  2. List all uploaded documents
                  3. Summarize a specific document
                
                Rules:
                - Always use the appropriate tool to find information before answering.
                - If the user asks what documents are available, use listUploadedDocuments with filter="".
                - If the user asks a question about document content, use searchDocuments.
                - If the user asks for a summary of a document, use summarizeDocument.
                - When calling listUploadedDocuments, always pass filter as an empty string "".
                - Be concise and clear in your final response.
                - If a tool returns no results, say so honestly.
                """;

        String response = chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .content();

        log.info("[AGENT] Response generated ({} chars)", response != null ? response.length() : 0);
        return response;
    }
}