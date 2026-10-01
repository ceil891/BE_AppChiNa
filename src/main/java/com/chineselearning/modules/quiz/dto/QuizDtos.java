package com.chineselearning.modules.quiz.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class QuizDtos {
    private QuizDtos() {}
    public record SetSummary(long id, String title, String description, Long lessonId, int questionCount) {}
    public record Start(@NotNull UUID requestId, @NotNull @Positive Long setId, @NotNull Integer questionCount) {}
    public record Answer(@NotNull UUID requestId, @NotNull UUID questionId, @NotNull UUID optionId,
                         @NotNull @PositiveOrZero Long version) {}
    public record Submit(@NotNull @PositiveOrZero Long version) {}
    public record Choice(UUID id, String text) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Question(UUID id, int position, String type, String prompt, List<Choice> options,
                           UUID selectedOptionId, UUID correctOptionId, Boolean correct, String explanation) {}
    public record Summary(UUID id, long setId, String title, String status, int questionCount, int answeredCount,
                          long version, OffsetDateTime createdAt, OffsetDateTime submittedAt, Integer correctCount, Integer score) {}
    public record Attempt(Summary attempt, List<Question> questions) {}
    public record AttemptPage(List<Summary> items, long total, int page, int size) {}
}
