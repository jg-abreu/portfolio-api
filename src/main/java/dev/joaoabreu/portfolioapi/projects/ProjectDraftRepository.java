package dev.joaoabreu.portfolioapi.projects;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectDraftRepository extends JpaRepository<ProjectDraft, Long> {
    List<ProjectDraft> findAllByOrderByDisplayOrderAscIdAsc();
}