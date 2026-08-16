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
        log.info("The returnd language type is {}",language.toString());
        if (language == null) {
            log.debug("Skipping unsupported file type: {}", filePath);
            return nodes;
        }

        try {
            TSParser parser = new TSParser();
            parser.setLanguage(language);

            TSTree tree = parser.parseString(null, fileContent);
            TSNode root = tree.getRootNode();

            List<String> imports = extractImports(root, fileContent,filePath);

            extractClasses(root, fileContent, filePath, repoUrl, branch, imports, nodes);

            extractMethods(root, fileContent, filePath, repoUrl, branch, imports, nodes);

            log.debug("Parsed {} nodes from {}", nodes.size(), filePath);

        } catch (Exception e) {
            log.warn("Failed to parse {}: {}", filePath, e.getMessage());
        }

        return nodes;
    }


    private List<String> extractImports(TSNode root, String source, String filePath) {
        List<String> imports = new ArrayList<>();

        if (filePath.endsWith(".py")) {
            // Python: import os, from os import path
            traverseForType(root, "import_statement", node -> {
                String text = getNodeText(node, source);
                extractLastIdentifier(text, imports);
            });
            traverseForType(root, "import_from_statement", node -> {
                String text = getNodeText(node, source);
                extractLastIdentifier(text, imports);
            });
        } else {
            // Java, JS, TS: import_declaration
            traverseForType(root, "import_declaration", node -> {
                String text = getNodeText(node, source);
                extractLastIdentifier(text, imports);
            });
        }

        return imports;
    }

    private void extractLastIdentifier(String importText, List<String> imports) {
        if (importText == null) return;
        String cleaned = importText
                .replace("import", "").replace("from", "")
                .replace(";", "").replace("*", "")
                .replace("{", "").replace("}", "")
                .trim();
        String[] parts = cleaned.split("[,\\s./]+");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isBlank() && trimmed.length() < 100
                    && !trimmed.equals("as") && !trimmed.equals("default")) {
                imports.add(trimmed);
            }
        }
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

        // Python: class declaration uses same node type name but different child structure
// tree-sitter-python uses "class_definition" not "class_declaration"
        if (filePath.endsWith(".py")) {
            traverseForType(root, "class_definition", node -> {
                String className = getChildByFieldName(node, "name", source);
                if (className == null || className.isBlank()) return;

                nodes.add(CodeGraphNode.builder()
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
                        .build());
            });
        }
    }

    private void extractMethods(TSNode root, String source, String filePath,
                                String repoUrl, String branch,
                                List<String> imports, List<CodeGraphNode> nodes) {

        for (String methodType : getMethodNodeTypes(filePath)) {
            traverseForType(root, methodType, node -> {
                String methodName = extractMethodName(node, source, filePath);
                if (methodName == null || methodName.isBlank()) return;

                List<String> calls = extractMethodCalls(node, source, filePath);

                nodes.add(CodeGraphNode.builder()
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
                        .build());
            });
        }
    }

    /**
     * Extracts the name of a function/method node.
     * Each language stores the name differently in the AST:
     *
     * Java:   method_declaration → name field → identifier
     * Python: function_definition → name field → identifier
     * JS/TS:  function_declaration → name field → identifier
     *         arrow_function → no name field → look at parent variable_declarator
     *         method_definition → first property_identifier or identifier child
     */
    private String extractMethodName(TSNode node, String source, String filePath) {

        // Strategy 1: standard "name" field — works for Java, Python, JS function_declaration
        String name = getChildByFieldName(node, "name", source);
        if (name != null && !name.isBlank()) return name.trim();

        // Strategy 2: JS/TS arrow function — name lives on parent variable_declarator
        // e.g. const processPayment = (amount) => { ... }
        //      variable_declarator
        //        name: identifier → "processPayment"   ← what we want
        //        value: arrow_function                  ← current node
        if (node.getParent() != null) {
            TSNode parent = node.getParent();
            if ("variable_declarator".equals(parent.getType())) {
                name = getChildByFieldName(parent, "name", source);
                if (name != null && !name.isBlank()) return name.trim();
            }
        }

        // Strategy 3: JS/TS method_definition — name is a property_identifier or identifier child
        // e.g. class Foo { processPayment(amount) { ... } }
        //      method_definition
        //        property_identifier → "processPayment"  ← what we want
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            String childType = child.getType();
            if ("property_identifier".equals(childType) || "identifier".equals(childType)) {
                String text = getNodeText(child, source);
                if (text != null && !text.isBlank() && text.length() < 100) {
                    return text.trim();
                }
            }
        }

        return null;
    }

    private List<String> extractMethodCalls(TSNode methodNode, String source, String filePath) {
        List<String> calls = new ArrayList<>();

        // Each language uses a different node type for function/method calls
        String callNodeType = getCallNodeType(filePath);

        traverseForType(methodNode, callNodeType, callNode -> {
            try {
                String callText = getNodeText(callNode, source);
                if (callText == null || !callText.contains("(")) return;

                String beforeParen = callText.substring(0, callText.indexOf("(")).trim();
                if (beforeParen.isBlank()) return;

                // Extract just the method name — handle chained calls like obj.method()
                String[] parts = beforeParen.split("[.\\s]");
                String calledMethod = parts[parts.length - 1].trim();

                // Sanity check — skip empty, too long, or obviously not a method name
                if (!calledMethod.isBlank()
                        && calledMethod.length() < 100
                        && !calledMethod.contains("(")
                        && !calledMethod.contains("{")) {
                    calls.add(calledMethod);
                }
            } catch (Exception e) {
                log.info("Skip malformed nodes");
            }
        });

        return calls;
    }

    private String getCallNodeType(String filePath) {
        if (filePath.endsWith(".py"))  return "call";            // Python: call
        if (filePath.endsWith(".java")) return "method_invocation"; // Java: method_invocation
        return "call_expression";                                // JS, TS: call_expression
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
