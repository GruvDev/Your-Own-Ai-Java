package com.semanticdocs.rag;

import com.semanticdocs.document.Chunk;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * Which chunk backed which answer, and how strongly.
 *
 * <p>This little join table is what makes the citations in the UI clickable and auditable.
 * Six months later you can still ask "why did the system say that?" and get an exact answer -
 * a property most RAG demos do not have.
 */
@Entity
@Table(name = "message_citations")
public class MessageCitation {

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "message_id")
        private Long messageId;

        @Column(name = "chunk_id")
        private Long chunkId;

        protected Key() {
        }

        public Key(Long messageId, Long chunkId) {
            this.messageId = messageId;
            this.chunkId = chunkId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return Objects.equals(messageId, key.messageId)
                    && Objects.equals(chunkId, key.chunkId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(messageId, chunkId);
        }
    }

    @EmbeddedId
    private Key id = new Key();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("messageId")
    @JoinColumn(name = "message_id")
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("chunkId")
    @JoinColumn(name = "chunk_id")
    private Chunk chunk;

    @Column(nullable = false)
    private float score;

    @Column(name = "rank", nullable = false)
    private int rank;

    protected MessageCitation() {
    }

    public MessageCitation(Message message, Chunk chunk, float score, int rank) {
        this.message = message;
        this.chunk = chunk;
        this.score = score;
        this.rank = rank;
        this.id = new Key(message.getId(), chunk.getId());
    }

    public Message getMessage() { return message; }
    public Chunk getChunk() { return chunk; }
    public float getScore() { return score; }
    public int getRank() { return rank; }
}
