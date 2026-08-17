package com.semanticdocs.document;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChunkRepository extends JpaRepository<Chunk, Long> {

    List<Chunk> findByDocumentIdOrderByChunkIndex(Long documentId);

    /**
     * Loads the chunks behind a page of search hits.
     *
     * <p>The JOIN FETCH is the whole point. Without it, rendering 10 results that each call
     * chunk.getDocument().getFilename() fires 1 query for the chunks plus 10 more for the
     * documents - the N+1 problem. One join turns 11 round trips into 1.
     */
    @Query("SELECT c FROM Chunk c JOIN FETCH c.document WHERE c.id IN :ids")
    List<Chunk> findAllByIdWithDocument(@Param("ids") Collection<Long> ids);
}
