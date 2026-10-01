package com.chineselearning.modules.lesson.repository;

import com.chineselearning.modules.lesson.dto.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class LessonRepository {
    private final JdbcClient jdbc;
    public LessonRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static final String SUMMARY = """
        SELECT l.id, l.title, l.description, l.position, l.published,
               (SELECT COUNT(*) FROM lesson_vocabulary lv WHERE lv.lesson_id = l.id) AS word_count
        FROM lessons l
        """;

    public List<LessonSummary> findAll() {
        return jdbc.sql(SUMMARY + " ORDER BY l.position").query(LessonSummary.class).list();
    }

    public Optional<LessonSummary> findById(long id) {
        return jdbc.sql(SUMMARY + " WHERE l.id = :id").param("id", id).query(LessonSummary.class).optional();
    }

    public boolean publication(long id,boolean published) {
        return jdbc.sql("UPDATE lessons SET published=:published WHERE id=:id").param("id",id).param("published",published).update()>0;
    }

    public List<Word> findWords(long id) {
        return jdbc.sql("""
            SELECT v.id, v.hanzi, v.pinyin, v.meaning_vi,
                   v.example_hanzi, v.example_pinyin, v.example_meaning_vi
            FROM vocabulary v JOIN lesson_vocabulary lv ON lv.vocabulary_id = v.id
            WHERE lv.lesson_id = :id ORDER BY lv.position
            """).param("id", id).query(Word.class).list();
    }
}
