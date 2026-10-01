package com.chineselearning.modules.progress.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.util.*;
import java.time.OffsetDateTime;

@Repository
public class LearningRecordRepository {
    public record LearningRecord(UUID id, long lessonId, String note, OffsetDateTime completedAt) {}
    private final JdbcClient jdbc;
    public LearningRecordRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public List<LearningRecord> list(UUID owner) {
        return jdbc.sql("SELECT id,lesson_id,note,completed_at FROM learning_records WHERE user_id=:owner ORDER BY updated_at DESC,id")
            .param("owner",owner).query(LearningRecord.class).list();
    }
    public Optional<LearningRecord> get(UUID id, UUID owner) {
        return jdbc.sql("SELECT id,lesson_id,note,completed_at FROM learning_records WHERE id=:id AND user_id=:owner")
            .param("id",id).param("owner",owner).query(LearningRecord.class).optional();
    }
    public LearningRecord create(UUID owner, long lesson, String note) {
        return jdbc.sql("INSERT INTO learning_records(id,user_id,lesson_id,note) VALUES (:id,:owner,:lesson,:note) RETURNING id,lesson_id,note,completed_at")
            .param("id",UUID.randomUUID()).param("owner",owner).param("lesson",lesson).param("note",note)
            .query(LearningRecord.class).single();
    }
    public Optional<LearningRecord> update(UUID id, UUID owner, String note) {
        return jdbc.sql("UPDATE learning_records SET note=:note,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND user_id=:owner RETURNING id,lesson_id,note,completed_at")
            .param("id",id).param("owner",owner).param("note",note).query(LearningRecord.class).optional();
    }
    public LearningRecord complete(UUID owner, long lesson, OffsetDateTime at) {
        return jdbc.sql("""
            INSERT INTO learning_records(id,user_id,lesson_id,completed_at) VALUES (:id,:owner,:lesson,:at)
            ON CONFLICT (user_id,lesson_id) DO UPDATE
            SET completed_at = COALESCE(learning_records.completed_at, EXCLUDED.completed_at)
            RETURNING id,lesson_id,note,completed_at
            """).param("id",UUID.randomUUID()).param("owner",owner).param("lesson",lesson).param("at",at).query(LearningRecord.class).single();
    }
}
