package com.chineselearning.modules.progress.repository;

import com.chineselearning.modules.progress.dto.ProgressDtos.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;

@Repository
public class LearningEventRepository {
    private final JdbcClient jdbc;
    public LearningEventRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public void record(UUID owner, Kind kind, UUID source, OffsetDateTime at, String label, Long lesson, Integer score, String rating) {
        jdbc.sql("""
            INSERT INTO learning_events(user_id,kind,source_id,occurred_at,study_date,label,lesson_id,score,rating)
            SELECT id,:kind,:source,:at,(CAST(:at AS timestamptz) AT TIME ZONE time_zone)::date,:label,:lesson,:score,:rating
            FROM users WHERE id=:owner ON CONFLICT(user_id,kind,source_id) DO NOTHING
            """).param("owner",owner).param("kind",kind.name()).param("source",source).param("at",at).param("label",label)
            .param("lesson",lesson).param("score",score).param("rating",rating).update();
    }
    public List<LocalDate> activeDates(UUID owner, OffsetDateTime now) {
        return jdbc.sql("SELECT DISTINCT study_date FROM learning_events WHERE user_id=:owner AND occurred_at<=:now ORDER BY study_date")
            .param("owner",owner).param("now",now).query(LocalDate.class).list();
    }
    public Totals totals(UUID owner, OffsetDateTime now) {
        return jdbc.sql("""
            SELECT COUNT(*) FILTER(WHERE kind='LESSON_COMPLETED') AS completed_lessons,
                COUNT(*) FILTER(WHERE kind='FLASHCARD_REVIEWED') AS flashcard_reviews,
                COUNT(*) FILTER(WHERE kind='QUIZ_SUBMITTED') AS submitted_quizzes,
                ROUND(AVG(score) FILTER(WHERE kind='QUIZ_SUBMITTED'),1) AS average_quiz_score
            FROM learning_events WHERE user_id=:owner AND occurred_at<=:now
            """).param("owner",owner).param("now",now).query(Totals.class).single();
    }
    public List<Day> days(UUID owner, LocalDate from, LocalDate to, OffsetDateTime now) {
        return jdbc.sql("""
            SELECT study_date AS date,COUNT(*) FILTER(WHERE kind='LESSON_COMPLETED') AS completed_lessons,
                COUNT(*) FILTER(WHERE kind='FLASHCARD_REVIEWED') AS flashcard_reviews,
                COUNT(*) FILTER(WHERE kind='QUIZ_SUBMITTED') AS submitted_quizzes,COUNT(*) AS total_activities
            FROM learning_events WHERE user_id=:owner AND study_date BETWEEN :from AND :to AND occurred_at<=:now
            GROUP BY study_date ORDER BY study_date
            """).param("owner",owner).param("from",from).param("to",to).param("now",now).query(Day.class).list();
    }
    public Set<Long> completedLessons(UUID owner, OffsetDateTime now) {
        return new HashSet<>(jdbc.sql("SELECT DISTINCT lesson_id FROM learning_events WHERE user_id=:owner AND kind='LESSON_COMPLETED' AND occurred_at<=:now")
            .param("owner",owner).param("now",now).query(Long.class).list());
    }
    private static final String FILTER=" WHERE user_id=:owner AND occurred_at<=:now AND (:kind='' OR kind=:kind) AND (:date='' OR study_date=CAST(NULLIF(:date,'') AS date)) ";
    public List<Activity> list(UUID owner, String kind, String date, int page, int size, OffsetDateTime now) {
        return jdbc.sql("SELECT id,kind,source_id,occurred_at,study_date,label,lesson_id,score,rating FROM learning_events"+FILTER+" ORDER BY occurred_at DESC,id DESC LIMIT :size OFFSET :offset")
            .param("owner",owner).param("now",now).param("kind",kind).param("date",date).param("size",size).param("offset",(long)page*size)
            .query(Activity.class).list();
    }
    public long count(UUID owner, String kind, String date, OffsetDateTime now) {
        return jdbc.sql("SELECT COUNT(*) FROM learning_events"+FILTER).param("owner",owner).param("now",now).param("kind",kind).param("date",date).query(Long.class).single();
    }
}
