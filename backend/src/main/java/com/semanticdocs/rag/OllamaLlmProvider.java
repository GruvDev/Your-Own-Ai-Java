package com.semanticdocs.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semanticdocs.common.ApiExceptions;
import com.semanticdocs.config.AppProperties;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Generation through a local Ollama server.
 *
 * <p>The streaming path uses the JDK's own HttpClient rather than RestClient, because Ollama
 * replies with newline-delimited JSON and we want to hand each fragment to the caller the
 * moment it arrives instead of buffering the whole body.
 */
@Component
@ConditionalOnProperty(name = "semanticdocs.llm.provider", havingValue = "ollama",
        matchIfMissing = true)
public class OllamaLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlmProvider.class);

    private final RestClient client;
    private final HttpClient streamingClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String model;
    private final double temperature;

    public OllamaLlmProvider(RestClient ollamaRestClient, AppProperties properties) {
        this.client = ollamaRestClient;
        this.baseUrl = properties.getOllama().getBaseUrl();
        this.model = properties.getLlm().getModel();
        this.temperature = properties.getLlm().getTemperature();
        this.streamingClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private record ChatResponse(Message message) {
        record Message(String role, String content) {
        }
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        try {
            ChatResponse response = client.post()
                    .uri("/api/chat")
                    .body(requestBody(systemPrompt, userPrompt, false))
                    .retrieve()
                    .body(ChatResponse.class);
            if (response == null || response.message() == null) {
                throw new ApiExceptions.UpstreamException("Empty response from the model", null);
            }
            return response.message().content();
        } catch (RestClientException ex) {
            throw new ApiExceptions.UpstreamException(
                    "Cannot reach the language model. Is Ollama running?", ex);
        }
    }

    @Override
    public void completeStreaming(String systemPrompt, String userPrompt,
                                  Consumer<String> onToken) {
        try {
            String body = objectMapper.writeValueAsString(
                    requestBody(systemPrompt, userPrompt, true));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<InputStream> response = streamingClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                throw new ApiExceptions.UpstreamException(
                        "Model returned HTTP " + response.statusCode(), null);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JsonNode node = objectMapper.readTree(line);
                    JsonNode message = node.get("message");
                    if (message != null && message.hasNonNull("content")) {
                        String token = message.get("content").asText();
                        if (!token.isEmpty()) onToken.accept(token);
                    }
                    if (node.path("done").asBoolean(false)) break;
                }
            }
        } catch (java.io.IOException ex) {
            throw new ApiExceptions.UpstreamException("Streaming from the model failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiExceptions.UpstreamException("Streaming was interrupted", ex);
        }
    }

    private Map<String, Object> requestBody(String systemPrompt, String userPrompt,
                                            boolean stream) {
        return Map.of(
                "model", model,
                "stream", stream,
                "options", Map.of("temperature", temperature),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
    }

    @Override
    public String modelName() {
        return model;
    }
}
