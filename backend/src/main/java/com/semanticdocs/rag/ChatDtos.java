package com.semanticdocs.rag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class ChatDtos {

    public record AskRequest(
            @NotBlank @Size(max = 2000) String question,
            Long conversationId,   // null starts a new thread
            Long documentId) {     // null searches everything the user owns
    }

    public record CitationDto(
            int number,
            Long chunkId,
            Long documentId,
            String filename,
            int chunkIndex,
            float score,
            String snippet) {
    }

    public record AnswerResponse(
            Long conversationId,
            Long messageId,
            String answer,
            String model,
            long tookMillis,
            List<CitationDto> citations) {
    }

    public record MessageDto(
            Long id,
            String role,
            String content,
            Instant createdAt,
            List<CitationDto> citations) {
    }

    public record ConversationDto(
            Long id,
            String title,
            Long documentId,
            Instant createdAt) {

        public static ConversationDto from(Conversation conversation) {
            return new ConversationDto(
                    conversation.getId(),
                    conversation.getTitle(),
                    conversation.getDocument() == null ? null : conversation.getDocument().getId(),
                    conversation.getCreatedAt());
        }
    }

    private ChatDtos() {
    }
}
