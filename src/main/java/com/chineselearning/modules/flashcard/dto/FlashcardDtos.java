package com.chineselearning.modules.flashcard.dto;
import com.chineselearning.modules.lesson.dto.Word;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;

public final class FlashcardDtos {
    private FlashcardDtos() {}
    public enum Rating { REMEMBER, AGAIN }
    public record SaveWord(@NotNull @Positive Long vocabularyId) {}
    public record SubmitReview(@NotNull UUID requestId,@NotNull Rating rating,@NotNull @PositiveOrZero Long version) {}
    public record Card(UUID id,Word word,OffsetDateTime nextReviewAt,OffsetDateTime lastReviewedAt,
        int successStreak,int reviewCount,long version) {}
    public record CardLookup(Card card) {}
    public record Stats(long saved,long due,long reviewed,OffsetDateTime nextDueAt) {}
    public record CardPage(List<Card> items,long total,int page,int size,Stats stats,OffsetDateTime serverTime) {}
    public record SavedLesson(int wordCount) {}
    public record Review(UUID requestId,UUID cardId,long vocabularyId,String hanzi,String pinyin,String meaningVi,
        Rating rating,long requestVersion,long resultVersion,OffsetDateTime reviewedAt,OffsetDateTime nextReviewAt,
        int successStreak,int reviewCount) {}
    public record HistoryPage(List<Review> items,long total,int page,int size) {}
}
