package com.semanticdocs.rag;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    /**
     * Loads a thread with its citations and the chunk behind each one in a single query.
     * Rendering a 20-message thread without this fires dozens of follow-up selects - the
     * N+1 problem, and the reason JOIN FETCH exists.
     */
    @Query("""
           SELECT DISTINCT m FROM Message m
           LEFT JOIN FETCH m.citations c
           LEFT JOIN FETCH c.chunk ch
           LEFT JOIN FETCH ch.document
           WHERE m.conversation.id = :conversationId
           ORDER BY m.createdAt
           """)
    List<Message> findWithCitations(@Param("conversationId") Long conversationId);
}
