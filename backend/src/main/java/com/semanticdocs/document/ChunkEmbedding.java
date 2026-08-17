package com.semanticdocs.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;

/**
 * The vector for one chunk, stored as raw bytes.
 *
 * <p>768 floats as a JSON array is about 9 KB of text; as packed float32 it is exactly 3072
 * bytes and needs no parsing. At a million chunks that difference is gigabytes, so the
 * encoding choice is not a micro-optimisation.
 */
@Entity
@Table(name = "embeddings")
public class ChunkEmbedding {

    @Id
    @Column(name = "chunk_id")
    private Long chunkId;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private int dimension;

    @Column(nullable = false)
    private byte[] vector;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ChunkEmbedding() {
    }

    public ChunkEmbedding(Long chunkId, String model, float[] values) {
        this.chunkId = chunkId;
        this.model = model;
        this.dimension = values.length;
        this.vector = toBytes(values);
    }

    public Long getChunkId() { return chunkId; }
    public String getModel() { return model; }
    public int getDimension() { return dimension; }

    public float[] toFloats() {
        return fromBytes(vector, dimension);
    }

    public static byte[] toBytes(float[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    public static float[] fromBytes(byte[] bytes, int dimension) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            values[i] = buffer.getFloat();
        }
        return values;
    }
}
