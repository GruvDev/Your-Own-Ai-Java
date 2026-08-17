package com.semanticdocs.embedding;

import com.semanticdocs.common.ApiExceptions;
import com.semanticdocs.config.AppProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls a locally running Ollama server to embed text.
 *
 * <p>Local embedding is not a compromise here, it is the right call: ingesting one book
 * produces thousands of chunks, and paying per token to embed them - repeatedly, every time
 * you tune the chunk size - would be both slow and expensive. Embeddings are also the piece
 * that must stay consistent: change the model and every stored vector becomes meaningless,
 * which is why the model name is saved with each row.
 */
@Component
@ConditionalOnProperty(name = "semanticdocs.embedding.provider", havingValue = "ollama",
        matchIfMissing = true)
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingProvider.class);

    private final RestClient client;
    private final String model;
    private final int dimension;
    private final int batchSize;
    private final String documentPrefix;
    private final String queryPrefix;

    public OllamaEmbeddingProvider(RestClient ollamaRestClient, AppProperties properties) {
        this.client = ollamaRestClient;
        this.model = properties.getEmbedding().getModel();
        this.dimension = properties.getEmbedding().getDimension();
        this.batchSize = properties.getEmbedding().getBatchSize();
        this.documentPrefix = properties.getEmbedding().getDocumentPrefix();
        this.queryPrefix = properties.getEmbedding().getQueryPrefix();
        log.info("Embedding with {} ({} dims), documentPrefix='{}', queryPrefix='{}'",
                model, dimension, documentPrefix, queryPrefix);
    }

    /** Ollama's response body for /api/embed. */
    private record EmbedResponse(List<List<Double>> embeddings) {
    }

    @Override
    public float[] embedDocument(String text) {
        return embedDocuments(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        List<String> prefixed = texts.stream().map(t -> documentPrefix + t).toList();
        List<float[]> results = new ArrayList<>(prefixed.size());
        for (int from = 0; from < prefixed.size(); from += batchSize) {
            int to = Math.min(from + batchSize, prefixed.size());
            results.addAll(callOllama(prefixed.subList(from, to)));
        }
        return results;
    }

    @Override
    public float[] embedQuery(String text) {
        return callOllama(List.of(queryPrefix + text)).get(0);
    }

    private List<float[]> callOllama(List<String> batch) {
        try {
            EmbedResponse response = client.post()
                    .uri("/api/embed")
                    .body(Map.of("model", model, "input", batch))
                    .retrieve()
                    .body(EmbedResponse.class);

            if (response == null || response.embeddings() == null
                    || response.embeddings().size() != batch.size()) {
                throw new ApiExceptions.UpstreamException(
                        "Ollama returned an unexpected embedding response", null);
            }
            List<float[]> vectors = new ArrayList<>(batch.size());
            for (List<Double> raw : response.embeddings()) {
                if (raw.size() != dimension) {
                    throw new ApiExceptions.UpstreamException(
                            "Model " + model + " returned " + raw.size() + " dimensions but "
                                    + "semanticdocs.embedding.dimension is " + dimension, null);
                }
                float[] vector = new float[raw.size()];
                for (int i = 0; i < raw.size(); i++) {
                    vector[i] = raw.get(i).floatValue();
                }
                vectors.add(vector);
            }
            return vectors;
        } catch (RestClientException ex) {
            log.error("Embedding call to Ollama failed", ex);
            throw new ApiExceptions.UpstreamException(
                    "Cannot reach the embedding model. Is Ollama running?", ex);
        }
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String modelName() {
        return model;
    }
}
