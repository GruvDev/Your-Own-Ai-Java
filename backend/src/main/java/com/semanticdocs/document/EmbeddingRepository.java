package com.semanticdocs.document;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmbeddingRepository extends JpaRepository<ChunkEmbedding, Long> {

    /** Used at startup to rebuild the in-memory index from the durable store. */
    @Query("SELECT e FROM ChunkEmbedding e ORDER BY e.chunkId")
    List<ChunkEmbedding> findAllOrdered();

    long countByModel(String model);
}
