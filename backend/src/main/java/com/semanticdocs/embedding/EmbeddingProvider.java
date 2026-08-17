package com.semanticdocs.embedding;

import java.util.List;

/**
 * Anything that can turn text into a vector.
 *
 * <p>Note that queries and documents are embedded through <b>different</b> methods. That is
 * not tidiness - several modern embedding models are asymmetric and expect to be told which
 * role the text is playing. {@code nomic-embed-text} is one: it was trained with the literal
 * prefixes {@code search_document:} and {@code search_query:}, and omitting them measurably
 * degrades ranking because every input lands in the region of the space the model reserves
 * for unlabelled text. Similarities end up compressed into a narrow band where relevant and
 * irrelevant passages score almost the same.
 *
 * <p>The interface exists so the rest of the application never mentions Ollama. Swapping to
 * OpenAI, to a local ONNX model, or to a fake in tests is a matter of supplying a different
 * bean - Dependency Inversion doing real work rather than appearing on a slide.
 */
public interface EmbeddingProvider {

    /** Embeds text that is being stored and later searched over. */
    float[] embedDocument(String text);

    /** Embeds many documents. Implementations may batch. */
    List<float[]> embedDocuments(List<String> texts);

    /** Embeds a search query. Must be used for queries, never {@link #embedDocument}. */
    float[] embedQuery(String text);

    /** Vector length this provider produces. Must match the index dimension. */
    int dimension();

    /** Model identifier, stored alongside each vector so we can detect a model change. */
    String modelName();
}
