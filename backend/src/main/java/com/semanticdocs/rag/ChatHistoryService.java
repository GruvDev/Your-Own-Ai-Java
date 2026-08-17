package com.semanticdocs.rag;

import com.semanticdocs.auth.CurrentUser;
import com.semanticdocs.auth.User;
import com.semanticdocs.common.ApiExceptions;
import com.semanticdocs.document.Chunk;
import com.semanticdocs.document.ChunkRepository;
import com.semanticdocs.document.Document;
import com.semanticdocs.document.DocumentRepository;
import com.semanticdocs.search.SearchDtos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything that touches the database on the chat side.
 *
 * <p>It lives in its own bean for a mechanical reason, not a stylistic one. Streaming an
 * answer takes tens of seconds, and holding a database transaction open for that long would
 * pin a connection from the pool the entire time - a handful of concurrent users would
 * exhaust the pool and the whole application would stall. So the long-running model call
 * happens outside any transaction, and this service opens short ones at the start and the end.
 */
@Service
public class ChatHistoryService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final CurrentUser currentUser;

    public ChatHistoryService(ConversationRepository conversationRepository,
                              MessageRepository messageRepository,
                              ChunkRepository chunkRepository,
                              DocumentRepository documentRepository,
                              CurrentUser currentUser) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.currentUser = currentUser;
    }

    /** Opens or continues a thread and records the user's question. Short transaction. */
    @Transactional
    public Conversation beginTurn(ChatDtos.AskRequest request) {
        User user = currentUser.require();
        Conversation conversation;

        if (request.conversationId() != null) {
            conversation = conversationRepository
                    .findByIdAndUserId(request.conversationId(), user.getId())
                    .orElseThrow(() -> new ApiExceptions.NotFoundException("No such conversation"));
        } else {
            Document document = null;
            if (request.documentId() != null) {
                document = documentRepository
                        .findByIdAndUserId(request.documentId(), user.getId())
                        .orElseThrow(() -> new ApiExceptions.NotFoundException("No such document"));
            }
            String question = request.question();
            String title = question.length() > 60 ? question.substring(0, 60) + "..." : question;
            conversation = conversationRepository.save(new Conversation(user, document, title));
        }
        messageRepository.save(new Message(conversation, "USER", request.question()));
        return conversation;
    }

    /** Stores the answer plus the passages it was based on. Short transaction. */
    @Transactional
    public Long saveAssistantMessage(Conversation conversation, String answer,
                                     List<SearchDtos.SearchResultItem> passages) {
        Message message = messageRepository.save(new Message(conversation, "ASSISTANT", answer));

        if (!passages.isEmpty()) {
            List<Long> chunkIds = passages.stream()
                    .map(SearchDtos.SearchResultItem::chunkId).toList();
            Map<Long, Chunk> chunksById = chunkRepository.findAllByIdWithDocument(chunkIds)
                    .stream().collect(Collectors.toMap(Chunk::getId, Function.identity()));

            List<MessageCitation> citations = new ArrayList<>(passages.size());
            for (int i = 0; i < passages.size(); i++) {
                SearchDtos.SearchResultItem passage = passages.get(i);
                Chunk chunk = chunksById.get(passage.chunkId());
                if (chunk != null) {
                    citations.add(new MessageCitation(message, chunk, passage.score(), i + 1));
                }
            }
            message.getCitations().addAll(citations);
            messageRepository.save(message);
        }
        return message.getId();
    }

    @Transactional(readOnly = true)
    public List<ChatDtos.ConversationDto> listConversations() {
        User user = currentUser.require();
        return conversationRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream().map(ChatDtos.ConversationDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ChatDtos.MessageDto> messages(Long conversationId) {
        User user = currentUser.require();
        conversationRepository.findByIdAndUserId(conversationId, user.getId())
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such conversation"));

        return messageRepository.findWithCitations(conversationId).stream()
                .map(message -> new ChatDtos.MessageDto(
                        message.getId(),
                        message.getRole(),
                        message.getContent(),
                        message.getCreatedAt(),
                        message.getCitations().stream()
                                .sorted((a, b) -> Integer.compare(a.getRank(), b.getRank()))
                                .map(citation -> new ChatDtos.CitationDto(
                                        citation.getRank(),
                                        citation.getChunk().getId(),
                                        citation.getChunk().getDocument().getId(),
                                        citation.getChunk().getDocument().getFilename(),
                                        citation.getChunk().getChunkIndex(),
                                        citation.getScore(),
                                        shorten(citation.getChunk().getContent())))
                                .toList()))
                .toList();
    }

    private String shorten(String content) {
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= 260 ? flat : flat.substring(0, 260) + "...";
    }
}
