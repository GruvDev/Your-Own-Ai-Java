package com.semanticdocs.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A slice of a document, roughly a few paragraphs.
 *
 * <p>Why slice at all? Two reasons. An embedding model has a fixed input limit, and a single
 * vector for a 50-page PDF would average away everything specific in it. Chunks are the unit
 * we embed, the unit we search, and the unit we cite.
 */
@Entity
@Table(name = "chunks")
public class Chunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id")
    private Document document;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "char_start", nullable = false)
    private int charStart;

    @Column(name = "char_end", nullable = false)
    private int charEnd;

    @Column(name = "page_number")
    private Integer pageNumber;

    protected Chunk() {
    }

    public Chunk(Document document, int chunkIndex, String content,
                 int charStart, int charEnd, Integer pageNumber) {
        this.document = document;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.charStart = charStart;
        this.charEnd = charEnd;
        this.pageNumber = pageNumber;
    }

    public Long getId() { return id; }
    public Document getDocument() { return document; }
    public int getChunkIndex() { return chunkIndex; }
    public String getContent() { return content; }
    public int getCharStart() { return charStart; }
    public int getCharEnd() { return charEnd; }
    public Integer getPageNumber() { return pageNumber; }
}
