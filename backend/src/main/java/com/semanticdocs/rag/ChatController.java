package com.semanticdocs.rag;

import com.semanticdocs.common.ApiExceptions;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final RagService ragService;
    private final ChatHistoryService history;
    private final Executor executor;

    public ChatController(RagService ragService,
                          ChatHistoryService history,
                          @Qualifier("streamingExecutor") Executor executor) {
        this.ragService = ragService;
        this.history = history;
        this.executor = executor;
    }

    /** Blocking. Returns once the model has finished. */
    @PostMapping("/ask")
    public ChatDtos.AnswerResponse ask(@Valid @RequestBody ChatDtos.AskRequest request) {
        return ragService.ask(request);
    }

    /**
     * Streaming answer over Server-Sent Events.
     *
     * <p>SSE rather than WebSocket, deliberately. The data only flows one way - server to
     * browser - so a full duplex socket buys nothing and costs a protocol upgrade, a
     * heartbeat, and reconnect handling. SSE is plain HTTP, reconnects on its own, and the
     * browser API is four lines.
     *
     * <p>The work runs on a separate thread so the servlet thread is released immediately;
     * SseEmitter is exactly Spring's mechanism for that.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatDtos.AskRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 minutes

        // The SecurityContext lives in a ThreadLocal, so it does not follow us onto the
        // worker thread by itself. We copy it across manually or the worker is anonymous.
        SecurityContext securityContext = SecurityContextHolder.getContext();

        executor.execute(() -> {
            SecurityContextHolder.setContext(securityContext);
            try {
                ragService.askStreaming(
                        request,
                        citations -> send(emitter, "citations", citations),
                        token -> send(emitter, "token", new TokenChunk(token)),
                        answer -> send(emitter, "done", answer));
                emitter.complete();
            } catch (ApiExceptions.UpstreamException ex) {
                log.warn("Streaming failed: {}", ex.getMessage());
                send(emitter, "error", new ErrorChunk(ex.getMessage()));
                emitter.complete();
            } catch (Exception ex) {
                log.error("Streaming failed", ex);
                emitter.completeWithError(ex);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        return emitter;
    }

    /**
     * One token of the answer, wrapped so it can be JSON-encoded.
     *
     * <p>Sending the raw token as SSE data looks simpler and is broken in two ways. First,
     * Spring writes the frame as "data:" immediately followed by the content, so a token that
     * begins with a space arrives as "data: report" and any client that strips the separator
     * space - which the SSE spec tells it to do - eats the space that was actually content.
     * The answer arrives with every word jammed together. Second, a token containing a
     * newline would split the frame in two and corrupt the stream entirely.
     *
     * <p>JSON encoding fixes both: whitespace is preserved exactly and newlines are escaped,
     * so a frame is always exactly one line.
     */
    public record TokenChunk(String t) {
    }

    public record ErrorChunk(String message) {
    }

    /**
     * Every event is sent as JSON, including tokens. Uniformity matters more than saving a
     * few bytes: the client has one parse path and no special case that can silently mangle
     * whitespace.
     */
    private void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data, MediaType.APPLICATION_JSON));
        } catch (IOException ex) {
            // Almost always the user closing the tab. Not worth an error log.
            log.debug("Client disconnected during streaming");
        }
    }

    @GetMapping("/conversations")
    public List<ChatDtos.ConversationDto> conversations() {
        return history.listConversations();
    }

    @GetMapping("/conversations/{id}/messages")
    public List<ChatDtos.MessageDto> messages(@PathVariable Long id) {
        return history.messages(id);
    }
}
