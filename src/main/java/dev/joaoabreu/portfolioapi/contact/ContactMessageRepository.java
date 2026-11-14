package dev.joaoabreu.portfolioapi.contact;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContactMessageRepository extends JpaRepository<ContactMessage, Long> {
    Page<ContactMessage> findByArchivedAtIsNull(Pageable pageable);
    Page<ContactMessage> findByArchivedAtIsNotNull(Pageable pageable);
}
