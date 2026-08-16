package spring.ai.demo.sprinAI.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import spring.ai.demo.sprinAI.Entity.CodeGraphNode;
import spring.ai.demo.sprinAI.Repository.CodeGraphRepository;

import java.util.*;
import java.util.stream.Collectors;


@Slf4j
@Service
@RequiredArgsConstructor
public class GraphRagService {

    private final VectorStore         vectorStore;
    private final CodeGraphRepository codeGraphRepository;
    private final ChatClient.Builder  chatClientBuilder;


    private static final int    MAX_DEPTH          = 3;    // max hops in the call graph
    private static final int    TOP_K_VECTOR       = 3;    // chunks from vector search
    private static final int MAX_GRAPH_NODES = 8; // cap graph results
    private static final double SIMILARITY_THRESHOLD = 0.1;

    public record GraphRagResponse(
            String answer,
            List<String> vectorSources,
            List<String> graphSources,
            List<String> traversalPath
    ) {}

    public GraphRagResponse queryRepo(String question, String repoUrl, String branch) {
        log.info("[GRAPH-RAG] Question: '{}' on {}/{}", question, repoUrl, branch);

        List<Document> vectorChunks = vectorSearch(question, repoUrl, branch);
        log.info("[GRAPH-RAG] Vector search found {} chunks", vectorChunks.size());

        Set<String> seedNodeNames = extractNodeNamesFromChunks(vectorChunks, repoUrl, branch);
        log.info("[GRAPH-RAG] Seed nodes for graph traversal: {}", seedNodeNames);

        Set<String>       visited        = new HashSet<>();
        List<String>      traversalPath  = new ArrayList<>();
        List<CodeGraphNode> graphNodes   = new ArrayList<>();

        for (String seedName : seedNodeNames) {
            traverseGraph(seedName, repoUrl, branch, 0, visited, traversalPath, graphNodes);
        }
        log.info("[GRAPH-RAG] Graph traversal found {} additional nodes", graphNodes.size());

        String vectorContext = buildVectorContext(vectorChunks);
        String graphContext  = buildGraphContext(graphNodes);

        List<String> vectorSources = vectorChunks.stream()
                .map(d -> String.valueOf(d.getMetadata().getOrDefault("source_file", "unknown")))
                .distinct().toList();

        List<String> graphSources = graphNodes.stream()
                .map(CodeGraphNode::getFilePath)
                .distinct().toList();

        String systemPrompt = """
                You are a code intelligence assistant. You have been given two types of context:
                
                1. VECTOR CONTEXT: Code chunks semantically similar to the question
                2. GRAPH CONTEXT: Structurally connected code found by following the call graph
                
                Use BOTH contexts to answer the question. The graph context often contains
                the actual implementation details that the vector context points to but doesn't
                include directly.
                
                Rules:
                - Cite specific file names and method names in your answer
                - Explain the flow of execution when relevant
                - If you see a call chain, describe it step by step
                - Be specific and technical — the user is a developer
                
                VECTOR CONTEXT (semantically similar code):
                %s
                
                GRAPH CONTEXT (structurally connected code via call graph):
                %s
                """.formatted(vectorContext, graphContext);

        String answer = chatClientBuilder.build()
                .prompt()
                .system(systemPrompt)
                .user(question)
                .call()
                .content();

        return new GraphRagResponse(answer, vectorSources, graphSources, traversalPath);
    }


    private void traverseGraph(String nodeName, String repoUrl, String branch,
                               int depth, Set<String> visited,
                               List<String> traversalPath, List<CodeGraphNode> result) {

        if (depth > MAX_DEPTH)          return;
        if (visited.contains(nodeName)) return;  // cycle prevention

        visited.add(nodeName);

        List<CodeGraphNode> nodes = codeGraphRepository
                .findByRepoUrlAndBranchAndNodeName(repoUrl, branch, nodeName);

        if (nodes.isEmpty()) return;

        result.addAll(nodes);

        nodes.forEach(node ->
                traversalPath.add("depth=" + depth + " → " + nodeName
                        + " in " + node.getFilePath())
        );

        for (CodeGraphNode node : nodes) {
            List<String> calls = parseJsonArray(node.getCalls());
            for (String calledMethod : calls) {
                traverseGraph(calledMethod, repoUrl, branch,
                        depth + 1, visited, traversalPath, result);
            }
        }

        log.info("Just before finding  {}",nodeName);
        List<CodeGraphNode> callers =
                codeGraphRepository.findCallers(repoUrl, branch, nodeName);
        for (CodeGraphNode caller : callers) {
            traverseGraph(caller.getNodeName(), repoUrl, branch,
                    depth + 1, visited, traversalPath, result);
        }
    }


    private List<Document> vectorSearch(String question, String repoUrl, String branch) {
        try {
            var request = SearchRequest.builder()
                    .query(question)
                    .topK(TOP_K_VECTOR)
                    .similarityThreshold(SIMILARITY_THRESHOLD)
                    .filterExpression(
                            "repo_url == '" + repoUrl + "' && branch == '" + branch + "'")
                    .build();
            return vectorStore.similaritySearch(request);
        } catch (Exception e) {
            log.warn("[GRAPH-RAG] Vector search failed: {}", e.getMessage());
            return List.of();
        }
    }


    private Set<String> extractNodeNamesFromChunks(
            List<Document> chunks, String repoUrl, String branch) {
        Set<String> names = new LinkedHashSet<>();
        for (Document chunk : chunks) {
            String filePath = String.valueOf(
                    chunk.getMetadata().getOrDefault("source_file", ""));
            if (!filePath.isBlank()) {
                codeGraphRepository
                        .findByRepoUrlAndBranchAndFilePath(repoUrl, branch, filePath)
                        .forEach(node -> names.add(node.getNodeName()));
            }
        }
        return names;
    }

    private String buildVectorContext(List<Document> chunks) {
        if (chunks.isEmpty()) return "No vector results found.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            Document d = chunks.get(i);
            String source = String.valueOf(d.getMetadata().getOrDefault("source_file", "unknown"));
            String text = d.getText() != null ? d.getText() : "";

            String trimmed = text.length() > 300 ? text.substring(0, 300) + "..." : text;

            sb.append("[").append(i + 1).append("] ").append(source).append("\n")
                    .append(trimmed).append("\n\n");
        }
        return sb.toString();
    }

    private String buildGraphContext(List<CodeGraphNode> nodes) {
        if (nodes.isEmpty()) return "No additional nodes found via graph traversal.";

        List<CodeGraphNode> deduped = nodes.stream()
                .filter(n -> n.getNodeName() != null)
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(
                                n -> n.getNodeName() + "|" + n.getFilePath(),
                                n -> n,
                                (a, b) -> a  // keep first on duplicate
                        ),
                        map -> new ArrayList<>(map.values())
                ));

        if (deduped.size() > MAX_GRAPH_NODES) {
            deduped = deduped.subList(0, MAX_GRAPH_NODES);
        }

        StringBuilder sb = new StringBuilder();
        for (CodeGraphNode node : deduped) {
            sb.append("File: ").append(node.getFilePath()).append("\n");
            sb.append("Node: ").append(node.getNodeName())
                    .append(" (").append(node.getNodeType()).append(")\n");

            String calls = node.getCalls();
            if (calls != null && calls.length() > 150) {
                calls = calls.substring(0, 150) + "...]";
            }
            sb.append("Calls: ").append(calls).append("\n\n");
        }
        return sb.toString();
    }

    private List<String> parseJsonArray(String jsonArray) {
        if (jsonArray == null || jsonArray.equals("[]")) return List.of();
        return Arrays.stream(
                        jsonArray.replace("[", "").replace("]", "").split(","))
                .map(s -> s.replace("\"", "").trim())
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }
}
