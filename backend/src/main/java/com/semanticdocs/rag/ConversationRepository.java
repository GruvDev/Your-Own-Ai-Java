package com.semanticdocs.rag;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** Ownership is part of the query, so a guessed id returns empty instead of someone else's chat. */
    Optional<Conversation> findByIdAndUserId(Long id, Long userId);
}
