package com.semanticdocs.rag;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CitationRepository extends JpaRepository<MessageCitation, MessageCitation.Key> {
}
