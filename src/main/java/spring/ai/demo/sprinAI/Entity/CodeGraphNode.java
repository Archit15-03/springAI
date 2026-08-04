package spring.ai.demo.sprinAI.Entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.Type;
import spring.ai.demo.sprinAI.configs.JsonbConverter;

import java.time.LocalDateTime;
import java.util.UUID;


@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "code_graph",
        indexes = {
                @Index(name = "idx_code_graph_repo_branch", columnList = "repo_url, branch"),
                @Index(name = "idx_code_graph_node_name",   columnList = "node_name"),
                @Index(name = "idx_code_graph_file",        columnList = "file_path")
        })
public class CodeGraphNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "repo_url", nullable = false, length = 1000)
    private String repoUrl;

    @Column(name = "branch", nullable = false, length = 100)
    @Builder.Default
    private String branch = "main";

    @Column(name = "file_path", nullable = false, length = 1000)
    private String filePath;

    @Column(name = "node_name", nullable = false, length = 500)
    private String nodeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", nullable = false, length = 50)
    private NodeType nodeType;

    @Column(name = "calls", columnDefinition = "text")
    @Builder.Default
    private String calls = "[]";

    @Column(name = "imports", columnDefinition = "text")
    @Builder.Default
    private String imports = "[]";

    @Column(name = "tags", columnDefinition = "text")
    @Builder.Default
    private String tags = "[]";

    @Column(name = "start_line")
    private Integer startLine;

    @Column(name = "end_line")
    private Integer endLine;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum NodeType {
        FUNCTION,
        METHOD,
        CLASS,
        INTERFACE
    }
}
