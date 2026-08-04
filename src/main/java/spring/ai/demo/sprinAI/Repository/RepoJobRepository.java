package spring.ai.demo.sprinAI.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import spring.ai.demo.sprinAI.Entity.RepoJob;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RepoJobRepository extends JpaRepository<RepoJob, UUID> {

    Optional<RepoJob> findByRepoUrlAndBranch(String repoUrl, String branch);

    boolean existsByRepoUrlAndBranch(String repoUrl, String branch);
}
