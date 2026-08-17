package com.semanticdocs.rag;

import java.util.function.Consumer;

/**
 * Anything that can answer a prompt.
 *
 * <p>Same idea as EmbeddingProvider: the RAG service must not know whether the model runs on
 * localhost or in someone's data centre. That is what lets the project demo offline on a
 * laptop and still switch to a hosted model with one line of configuration.
 */
public interface LlmProvider {

    /** Blocking call. Returns the whole answer. */
    String complete(String systemPrompt, String userPrompt);

    /**
     * Streaming call. Invokes the consumer for each token as it arrives.
     *
     * <p>Streaming is not a gimmick: a 3B model on a CPU can take 20 seconds to finish an
     * answer, and watching words appear feels immediate while watching a spinner does not.
     * Same total time, completely different experience.
     */
    void completeStreaming(String systemPrompt, String userPrompt, Consumer<String> onToken);

    String modelName();
}
