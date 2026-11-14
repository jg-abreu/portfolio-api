package dev.joaoabreu.portfolioapi.projects;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectDraftRepository extends JpaRepository<ProjectDraft, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select draft from ProjectDraft draft where draft.id = :id")
    Optional<ProjectDraft> findForPublication(@Param("id") long id);

    List<ProjectDraft> findAllByOrderByDisplayOrderAscIdAsc();
}
