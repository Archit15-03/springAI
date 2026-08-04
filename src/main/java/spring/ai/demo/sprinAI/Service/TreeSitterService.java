package spring.ai.demo.sprinAI.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.treesitter.*;
import spring.ai.demo.sprinAI.Entity.CodeGraphNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;


@Slf4j
@Service
public class TreeSitterService {

    private TSLanguage getLanguageForFile(String filePath) {
        if (filePath.endsWith(".java"))  return new TreeSitterJava();
        if (filePath.endsWith(".js"))    return new TreeSitterJavascript();
        if (filePath.endsWith(".ts"))    return new TreeSitterTypescript();
        if (filePath.endsWith(".py"))    return new TreeSitterPython();
        if (filePath.endsWith(".go"))    return new TreeSitterGo();
        if (filePath.endsWith(".rs"))    return new TreeSitterRust();
        return null;
    }

    private List<String> getMethodNodeTypes(String filePath) {
        if (filePath.endsWith(".java"))  return List.of("method_declaration");
        if (filePath.endsWith(".js") || filePath.endsWith(".ts"))
            return List.of("function_declaration", "method_definition", "arrow_function");
        if (filePath.endsWith(".py"))    return List.of("function_definition");
        if (filePath.endsWith(".go"))    return List.of("function_declaration", "method_declaration");
        if (filePath.endsWith(".rs"))    return List.of("function_item");
        return List.of("function_declaration");
    }


    public List<CodeGraphNode> parseCodeFile(
            String filePath, String fileContent, String repoUrl, String branch) {

        List<CodeGraphNode> nodes = new ArrayList<>();

        TSLanguage language = getLanguageForFile(filePath);
        if (language == null) {
            log.debug("Skipping unsupported file type: {}", filePath);
            return nodes;
        }

        try {
            TSParser parser = new TSParser();
            parser.setLanguage(language);

            TSTree tree = parser.parseString(null, fileContent);
            TSNode root = tree.getRootNode();

            List<String> imports = extractImports(root, fileContent);

            extractClasses(root, fileContent, filePath, repoUrl, branch, imports, nodes);

            extractMethods(root, fileContent, filePath, repoUrl, branch, imports, nodes);

            log.debug("Parsed {} nodes from {}", nodes.size(), filePath);

        } catch (Exception e) {
            log.warn("Failed to parse {}: {}", filePath, e.getMessage());
        }

        return nodes;
    }


    private List<String> extractImports(TSNode root, String source) {
        List<String> imports = new ArrayList<>();
        traverseForType(root, "import_declaration", node -> {
            String importText = getNodeText(node, source);
            String[] parts = importText.replace("import", "").replace(";", "").trim().split("\\.");
            if (parts.length > 0) {
                imports.add(parts[parts.length - 1].trim());
            }
        });
        return imports;
    }

    private void extractClasses(TSNode root, String source, String filePath,
                                String repoUrl, String branch,
                                List<String> imports, List<CodeGraphNode> nodes) {
        traverseForType(root, "class_declaration", node -> {
            String className = getChildByFieldName(node, "name", source);
            if (className == null || className.isBlank()) return;

            CodeGraphNode graphNode = CodeGraphNode.builder()
                    .repoUrl(repoUrl)
                    .branch(branch)
                    .filePath(filePath)
                    .nodeName(className)
                    .nodeType(CodeGraphNode.NodeType.CLASS)
                    .imports(toJsonArray(imports))
                    .calls("[]")
                    .tags(inferTags(className, filePath))
                    .startLine(node.getStartPoint().getRow() + 1)
                    .endLine(node.getEndPoint().getRow() + 1)
                    .build();

            nodes.add(graphNode);
        });

        traverseForType(root, "interface_declaration", node -> {
            String name = getChildByFieldName(node, "name", source);
            if (name == null || name.isBlank()) return;

            nodes.add(CodeGraphNode.builder()
                    .repoUrl(repoUrl)
                    .branch(branch)
                    .filePath(filePath)
                    .nodeName(name)
                    .nodeType(CodeGraphNode.NodeType.INTERFACE)
                    .imports(toJsonArray(imports))
                    .calls("[]")
                    .tags(inferTags(name, filePath))
                    .startLine(node.getStartPoint().getRow() + 1)
                    .endLine(node.getEndPoint().getRow() + 1)
                    .build());
        });
    }

    private void extractMethods(TSNode root, String source, String filePath,
                                String repoUrl, String branch,
                                List<String> imports, List<CodeGraphNode> nodes) {

        for (String methodType : getMethodNodeTypes(filePath)) {
            traverseForType(root, "method_declaration", node -> {
                String methodName = getChildByFieldName(node, "name", source);
                if (methodName == null || methodName.isBlank()) return;

                List<String> calls = extractMethodCalls(node, source);

                CodeGraphNode graphNode = CodeGraphNode.builder()
                        .repoUrl(repoUrl)
                        .branch(branch)
                        .filePath(filePath)
                        .nodeName(methodName)
                        .nodeType(CodeGraphNode.NodeType.METHOD)
                        .calls(toJsonArray(calls))
                        .imports(toJsonArray(imports))
                        .tags(inferTags(methodName, filePath))
                        .startLine(node.getStartPoint().getRow() + 1)
                        .endLine(node.getEndPoint().getRow() + 1)
                        .build();

                nodes.add(graphNode);
            });
        }
    }

    private List<String> extractMethodCalls(TSNode methodNode, String source) {
        List<String> calls = new ArrayList<>();
        traverseForType(methodNode, "method_invocation", callNode -> {
            String callText = getNodeText(callNode, source);
            if (callText.contains("(")) {
                String beforeParen = callText.substring(0, callText.indexOf("("));
                String[] parts = beforeParen.split("\\.");
                String calledMethod = parts[parts.length - 1].trim();
                if (!calledMethod.isBlank()) {
                    calls.add(calledMethod);
                }
            }
        });
        return calls;
    }

    private String inferTags(String name, String filePath) {
        List<String> tags = new ArrayList<>();
        String combined = (name + " " + filePath).toLowerCase();

        if (combined.contains("payment") || combined.contains("charge") || combined.contains("billing"))
            tags.add("payment");
        if (combined.contains("auth") || combined.contains("login") || combined.contains("token") || combined.contains("security"))
            tags.add("auth");
        if (combined.contains("controller") || combined.contains("api") || combined.contains("endpoint"))
            tags.add("api-route");
        if (combined.contains("repository") || combined.contains("dao") || combined.contains("jpa"))
            tags.add("persistence");
        if (combined.contains("service"))
            tags.add("service");
        if (combined.contains("config") || combined.contains("configuration"))
            tags.add("config");
        if (combined.contains("user") || combined.contains("account") || combined.contains("profile"))
            tags.add("user");
        if (combined.contains("email") || combined.contains("notification") || combined.contains("mail"))
            tags.add("notification");

        return toJsonArray(tags);
    }


    @FunctionalInterface
    private interface NodeVisitor {
        void visit(TSNode node);
    }

    private void traverseForType(TSNode node, String targetType, NodeVisitor visitor) {
        if (node == null) return;

        if (targetType.equals(node.getType())) {
            visitor.visit(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            traverseForType(node.getChild(i), targetType, visitor);
        }
    }

    private String getNodeText(TSNode node, String source) {
        int start = node.getStartByte();
        int end   = node.getEndByte();
        if (start >= 0 && end <= source.length() && start < end) {
            return source.substring(start, end);
        }
        return "";
    }

    private String getChildByFieldName(TSNode node, String fieldName, String source) {
        TSNode child = node.getChildByFieldName(fieldName);
        if (child == null) return null;
        return getNodeText(child, source);
    }

    private String toJsonArray(List<String> items) {
        if (items == null || items.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            sb.append("\"").append(items.get(i).replace("\"", "\\\"")).append("\"");
            if (i < items.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
