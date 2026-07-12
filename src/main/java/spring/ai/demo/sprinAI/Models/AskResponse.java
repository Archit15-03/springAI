package spring.ai.demo.sprinAI.Models;

import java.util.List;

public record AskResponse(String answer, List<SourceChunk> sources) {

    public record SourceChunk(String sourceFile, String snippet, Double score) {
    }
}
