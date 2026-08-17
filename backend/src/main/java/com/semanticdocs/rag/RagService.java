package com.semanticdocs.rag;

import com.semanticdocs.config.AppProperties;
import com.semanticdocs.search.SearchDtos;
import com.semanticdocs.search.SearchService;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Retrieval Augmented Generation: search first, then answer from what was found.
 *
 * <p>The name sounds heavier than the idea. A language model knows nothing about your PDFs,
 * and asking it directly produces confident fiction. So we do the retrieval ourselves - that
 * is what the vector index is for - and paste the best passages into the prompt. The model's
 * job shrinks from "know everything" to "read these six paragraphs and answer", which is a
 * job it is genuinely good at.
 *
 * <p>Note what this class does NOT do: it opens no transaction and holds no database
 * connection while the model is generating. Persistence is delegated to ChatHistoryService in
 * two short bursts, one before and one after.
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    /** Characters of passage text we allow, expressed as a token budget. */
    private static final int CONTEXT_TOKEN_BUDGET = 2400;

    private final SearchService searchService;
    private final LlmProvider llmProvider;
    private final PromptBuilder promptBuilder;
    private final ChatHistoryService history;
    private final AppProperties properties;

    public RagService(SearchService searchService,
                      LlmProvider llmProvider,
                      PromptBuilder promptBuilder,
                      ChatHistoryService history,
                      AppProperties properties) {
        this.searchService = searchService;
        this.llmProvider = llmProvider;
        this.promptBuilder = promptBuilder;
        this.history = history;
        this.properties = properties;
    }

    private record Prepared(
            Conversation conversation,
            List<SearchDtos.SearchResultItem> passages,
            String systemPrompt,
            String userPrompt,
            int redactions) {
    }

    /** Retrieval and prompt assembly. No model call yet. */
    private Prepared prepare(ChatDtos.AskRequest request) {
        Conversation conversation = history.beginTurn(request);
        int maxPassages = properties.getLlm().getMaxContextChunks();

        // Ask for more than we will use: some get filtered by ownership or by the document
        // filter, and the context budget may not fit them all anyway.
        SearchDtos.SearchResponse search = searchService.search(new SearchDtos.SearchRequest(
                request.question(), maxPassages * 2, request.documentId(), null));

        PromptBuilder.Built built = promptBuilder.build(
                request.question(), search.results(), maxPassages, CONTEXT_TOKEN_BUDGET);

        List<SearchDtos.SearchResultItem> passages = new ArrayList<>(
                search.results().subList(0, Math.min(built.passagesUsed(), search.results().size())));

        if (built.redactions() > 0) {
            log.warn("Redacted {} instruction-like line(s) from passages for question: {}",
                    built.redactions(), request.question());
        }

        return new Prepared(conversation, passages, promptBuilder.systemPrompt(),
                built.prompt(), built.redactions());
    }

    /** Blocking answer. */
    public ChatDtos.AnswerResponse ask(ChatDtos.AskRequest request) {
        long start = System.nanoTime();
        Prepared prepared = prepare(request);

        String answer = prepared.passages().isEmpty()
                ? noResultsMessage()
                : guard(llmProvider.complete(prepared.systemPrompt(), prepared.userPrompt()),
                        prepared);

        return finish(prepared, answer, start);
    }

    /** Streaming answer: citations first, then tokens, then a final summary event. */
    public void askStreaming(ChatDtos.AskRequest request,
                             Consumer<List<ChatDtos.CitationDto>> onCitations,
                             Consumer<String> onToken,
                             Consumer<ChatDtos.AnswerResponse> onComplete) {
        long start = System.nanoTime();
        Prepared prepared = prepare(request);

        // Citations go out before generation starts. The user can begin reading the sources
        // while the model is still writing, which is most of the perceived speed-up.
        onCitations.accept(toCitationDtos(prepared.passages()));

        if (prepared.passages().isEmpty()) {
            String message = noResultsMessage();
            onToken.accept(message);
            onComplete.accept(finish(prepared, message, start));
            return;
        }

        StringBuilder assembled = new StringBuilder();
        llmProvider.completeStreaming(prepared.systemPrompt(), prepared.userPrompt(), token -> {
            assembled.append(token);
            onToken.accept(token);
        });

        // The guard runs on the assembled answer. Tokens have already been shown, so this
        // cannot un-display a bad answer mid-stream - it corrects what gets stored and what
        // the final event reports. Catching it before the first token would need the whole
        // answer before showing any of it, which throws away the entire benefit of streaming.
        onComplete.accept(finish(prepared, guard(assembled.toString(), prepared), start));
    }

    private ChatDtos.AnswerResponse finish(Prepared prepared, String answer, long startNanos) {
        Long messageId = history.saveAssistantMessage(
                prepared.conversation(), answer, prepared.passages());
        long took = (System.nanoTime() - startNanos) / 1_000_000;
        log.debug("Answered in {} ms using {} passages", took, prepared.passages().size());

        return new ChatDtos.AnswerResponse(
                prepared.conversation().getId(),
                messageId,
                answer,
                llmProvider.modelName(),
                took,
                toCitationDtos(prepared.passages()));
    }

    /**
     * Last line of defence: check the answer looks like an answer.
     *
     * <p>Two cheap signals catch a model that has been captured by injected text. An answer
     * with no citation at all, when passages were supplied and citations were demanded, did
     * not come from the passages. And an answer far shorter than any real answer to a document
     * question - a single word, typically - is the classic symptom of a successful injection.
     *
     * <p>Neither check is clever, and a determined attacker writes an injection that produces
     * a long, well-cited lie. They exist because a layer that catches the obvious cases is
     * worth more than no layer, and because a system that notices something went wrong is
     * better than one that silently returns the attacker's text.
     */
    private String guard(String answer, Prepared prepared) {
        if (answer == null || answer.isBlank()) {
            return "The model returned an empty answer. Try rephrasing the question.";
        }
        String trimmed = answer.strip();
        boolean hasCitation = trimmed.matches("(?s).*\\[\\d+].*");
        boolean suspiciouslyShort = trimmed.length() < 40;

        if (!hasCitation && suspiciouslyShort) {
            log.error("Rejected an answer with no citation and no substance. "
                    + "Likely prompt injection. Raw output: {}", trimmed);
            return "I could not produce a grounded answer to that. The retrieved passages "
                    + "contained text that attempted to override my instructions, and the "
                    + "answer was rejected. Try asking about a different document.";
        }
        if (prepared.redactions() > 0) {
            log.info("Answered from passages containing {} redacted line(s)",
                    prepared.redactions());
        }
        return answer;
    }

    /**
     * Saying "I don't know" is a feature. A RAG system that answers from general knowledge
     * when retrieval comes up empty is worse than useless, because the user cannot tell which
     * answers came from their documents and which the model made up.
     */
    private String noResultsMessage() {
        return "I could not find anything about that in your documents. "
                + "Try rephrasing, or upload a document that covers it.";
    }

    private List<ChatDtos.CitationDto> toCitationDtos(List<SearchDtos.SearchResultItem> passages) {
        List<ChatDtos.CitationDto> citations = new ArrayList<>(passages.size());
        for (int i = 0; i < passages.size(); i++) {
            SearchDtos.SearchResultItem passage = passages.get(i);
            citations.add(new ChatDtos.CitationDto(
                    i + 1,
                    passage.chunkId(),
                    passage.documentId(),
                    passage.filename(),
                    passage.chunkIndex(),
                    passage.score(),
                    passage.snippet()));
        }
        return citations;
    }
}
