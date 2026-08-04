package spring.ai.demo.sprinAI.Repository;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import spring.ai.demo.sprinAI.Entity.CodeGraphNode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CodeGraphRepository extends JpaRepository<CodeGraphNode, UUID> {

    List<CodeGraphNode> findByRepoUrlAndBranch(String repoUrl, String branch);

    List<CodeGraphNode> findByRepoUrlAndBranchAndNodeName(
            String repoUrl, String branch, String nodeName);

    List<CodeGraphNode> findByRepoUrlAndBranchAndFilePath(
            String repoUrl, String branch, String filePath);

    // Find all nodes that call a specific function — used for graph traversal
    @Query(value = """
        SELECT * FROM code_graph 
        WHERE repo_url = :repoUrl 
        AND branch = :branch 
        AND calls::jsonb @> CAST(CONCAT('["', :nodeName, '"]') AS jsonb)
        """, nativeQuery = true)
    List<CodeGraphNode> findCallers(String repoUrl, String branch, String nodeName);

    // Delete all nodes for a specific file — used during incremental sync
    @Modifying
    @Query("DELETE FROM CodeGraphNode c WHERE c.repoUrl = :repoUrl AND c.branch = :branch AND c.filePath = :filePath")
    void deleteByRepoUrlAndBranchAndFilePath(String repoUrl, String branch, String filePath);

    // Delete all nodes for a repo+branch — used on full re-ingest
    @Modifying
    @Query("DELETE FROM CodeGraphNode c WHERE c.repoUrl = :repoUrl AND c.branch = :branch")
    void deleteByRepoUrlAndBranch(String repoUrl, String branch);
}
