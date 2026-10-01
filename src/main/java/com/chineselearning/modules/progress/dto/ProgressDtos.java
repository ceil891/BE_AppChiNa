package com.chineselearning.modules.progress.dto;

import com.chineselearning.modules.flashcard.dto.FlashcardDtos.Stats;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class ProgressDtos {
    private ProgressDtos() {}
    public enum Kind { LESSON_COMPLETED, FLASHCARD_REVIEWED, QUIZ_SUBMITTED, SKILL_PRACTICED, FOUNDATION_SUBMITTED }
    public record Streak(int current, int longest, int activeDays, LocalDate lastStudyDate, boolean studiedToday) {}
    public record Totals(long completedLessons, long flashcardReviews, long submittedQuizzes, BigDecimal averageQuizScore) {}
    public record Day(LocalDate date, long completedLessons, long flashcardReviews, long submittedQuizzes, long totalActivities) {}
    public record Activity(long id, Kind kind, UUID sourceId, OffsetDateTime occurredAt, LocalDate studyDate,
                           String label, Long lessonId, Integer score, String rating) {}
    public record ActivityPage(List<Activity> items, long total, int page, int size, String timeZone) {}
    public record NextAction(String kind, String label, String description, String path) {}
    public record Dashboard(String timeZone, LocalDate today, OffsetDateTime serverTime, OffsetDateTime nextDayAt,
                            Streak streak, Totals totals, long totalLessons, Stats flashcards,
                            Day todayActivity, List<Day> calendar, List<Activity> recentActivities, NextAction nextAction) {}
}
