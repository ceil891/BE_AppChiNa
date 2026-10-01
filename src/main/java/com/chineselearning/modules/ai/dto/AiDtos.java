package com.chineselearning.modules.ai.dto;

import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

public final class AiDtos {
    private AiDtos() {}
    public record Start(@NotNull UUID requestId, @Positive Long lessonId,
                        @Pattern(regexp="EXPLAIN|CORRECT|CONVERSATION|REVIEW") String mode) {
        public Start { if(mode==null)mode="EXPLAIN"; }
    }
    public record Rename(@NotBlank @Size(max=120) String title) {}
    public record Send(@NotNull UUID requestId, @NotBlank @Size(max=1500) String question, UUID retryOf) {}
    public record Conversation(UUID id, Long lessonId, String title, String promptVersion, OffsetDateTime createdAt, OffsetDateTime updatedAt,String mode) {}
    public record Turn(UUID id, UUID requestId, UUID retryOf, String question, String answer, String status,
                       String errorCode, String model, OffsetDateTime createdAt, OffsetDateTime finishedAt,
                       OffsetDateTime expiresAt, Integer inputTokens, Integer outputTokens, Integer totalTokens) {}
    public record Detail(Conversation conversation, List<Turn> turns) {}
    public record Page(List<Conversation> items,long total,int page,int size) {}
    public record Status(boolean available,String provider,int dailyLimit,int usedToday,int remainingToday,
                         int maxQuestionLength,int maxTurnsPerConversation,LocalDate today,OffsetDateTime resetsAt,String reason) {}
}
