package com.chineselearning.modules.flashcard.service;
import com.chineselearning.modules.flashcard.dto.FlashcardDtos.Rating;
import java.time.OffsetDateTime;

/** A simple MVP schedule, not a measurement of long-term mastery. */
public final class ReviewSchedule {
    private ReviewSchedule() {}
    private static final int[] DAYS={1,3,7,14,30};
    public record Next(int successStreak,OffsetDateTime dueAt) {}
    public static Next next(int streak,Rating rating,OffsetDateTime now) {
        if(rating==Rating.AGAIN)return new Next(0,now.plusMinutes(10));
        int next=Math.min(streak+1,DAYS.length);
        return new Next(next,now.plusDays(DAYS[next-1]));
    }
}
