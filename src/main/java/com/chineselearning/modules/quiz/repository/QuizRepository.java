package com.chineselearning.modules.quiz.repository;

import com.chineselearning.modules.quiz.dto.QuizDtos.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class QuizRepository {
    public record BankQuestion(String type, String prompt, List<String> options, int correctIndex, String explanation) {}
    public record StoredQuestion(UUID id, int position, String type, String prompt, List<Choice> options,
                                 UUID correctOptionId, UUID selectedOptionId, String explanation) {}
    public record Receipt(UUID questionId, UUID optionId, long requestVersion) {}
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    public QuizRepository(JdbcClient jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    private static final String SUMMARY = """
        SELECT a.id,a.source_set_id AS set_id,a.title,
          CASE WHEN a.submitted_at IS NULL THEN 'IN_PROGRESS' ELSE 'SUBMITTED' END AS status,
          a.question_count,(SELECT COUNT(*) FROM quiz_attempt_questions q WHERE q.attempt_id=a.id AND q.selected_option_id IS NOT NULL) AS answered_count,
          a.version,a.created_at,a.submitted_at,a.correct_count,a.score FROM quiz_attempts a
        """;
    public List<SetSummary> sets() {
        return jdbc.sql("""
            SELECT s.id,s.title,s.description,s.lesson_id,COUNT(q.id) AS question_count FROM quiz_sets s
            JOIN quiz_questions q ON q.set_id=s.id WHERE s.published GROUP BY s.id HAVING COUNT(q.id)>=5 ORDER BY s.id
            """).query(SetSummary.class).list();
    }
    public Optional<SetSummary> set(long id) { return sets().stream().filter(s -> s.id() == id).findFirst(); }
    public boolean lockSet(long id) {
        return jdbc.sql("SELECT id FROM quiz_sets WHERE id=:id FOR SHARE").param("id",id).query(Long.class).optional().isPresent();
    }
    public List<BankQuestion> bank(long id) {
        return jdbc.sql("SELECT type,prompt,options,correct_index,explanation FROM quiz_questions WHERE set_id=:id ORDER BY position")
            .param("id", id).query((rs, index) -> new BankQuestion(rs.getString("type"), rs.getString("prompt"),
                Arrays.asList(mapper.readValue(rs.getString("options"), String[].class)), rs.getInt("correct_index"), rs.getString("explanation"))).list();
    }
    public Optional<Summary> get(UUID owner, UUID id) {
        return jdbc.sql(SUMMARY + " WHERE a.user_id=:owner AND a.id=:id").param("owner", owner).param("id", id).query(Summary.class).optional();
    }
    public Optional<Summary> byRequest(UUID owner, UUID request) {
        return jdbc.sql(SUMMARY + " WHERE a.user_id=:owner AND a.request_id=:request").param("owner", owner).param("request", request).query(Summary.class).optional();
    }
    public boolean insert(UUID id, UUID owner, Start input, String title, OffsetDateTime now) {
        return jdbc.sql("""
            INSERT INTO quiz_attempts(id,user_id,request_id,source_set_id,title,question_count,created_at)
            VALUES (:id,:owner,:request,:set,:title,:count,:now) ON CONFLICT(user_id,request_id) DO NOTHING
            """).param("id", id).param("owner", owner).param("request", input.requestId()).param("set", input.setId())
            .param("title", title).param("count", input.questionCount()).param("now", now).update() == 1;
    }
    public void insertQuestion(UUID attempt, StoredQuestion q) {
        jdbc.sql("""
            INSERT INTO quiz_attempt_questions(id,attempt_id,position,type,prompt,options,correct_option_id,explanation)
            VALUES (:id,:attempt,:position,:type,:prompt,CAST(:options AS jsonb),:correct,:explanation)
            """).param("id", q.id()).param("attempt", attempt).param("position", q.position()).param("type", q.type())
            .param("prompt", q.prompt()).param("options", mapper.writeValueAsString(q.options()))
            .param("correct", q.correctOptionId()).param("explanation", q.explanation()).update();
    }
    public List<StoredQuestion> questions(UUID owner, UUID attempt) {
        RowMapper<StoredQuestion> row = (rs, index) -> new StoredQuestion(rs.getObject("id", UUID.class), rs.getInt("position"),
            rs.getString("type"), rs.getString("prompt"), Arrays.asList(mapper.readValue(rs.getString("options"), Choice[].class)),
            rs.getObject("correct_option_id", UUID.class), rs.getObject("selected_option_id", UUID.class), rs.getString("explanation"));
        return jdbc.sql("""
            SELECT q.* FROM quiz_attempt_questions q JOIN quiz_attempts a ON a.id=q.attempt_id
            WHERE a.id=:id AND a.user_id=:owner ORDER BY q.position
            """).param("id", attempt).param("owner", owner).query(row).list();
    }
    public boolean lock(UUID owner, UUID id) {
        return jdbc.sql("SELECT id FROM quiz_attempts WHERE id=:id AND user_id=:owner FOR UPDATE")
            .param("id", id).param("owner", owner).query(UUID.class).optional().isPresent();
    }
    public Optional<Receipt> receipt(UUID attempt, UUID request) {
        return jdbc.sql("SELECT question_id,option_id,request_version FROM quiz_answer_requests WHERE attempt_id=:id AND request_id=:request")
            .param("id", attempt).param("request", request).query(Receipt.class).optional();
    }
    public void answer(UUID owner, UUID id, Answer input) {
        jdbc.sql("""
            INSERT INTO quiz_answer_requests(attempt_id,request_id,question_id,option_id,request_version)
            VALUES (:id,:request,:question,:option,:version)
            """).param("id", id).param("request", input.requestId()).param("question", input.questionId())
            .param("option", input.optionId()).param("version", input.version()).update();
        jdbc.sql("UPDATE quiz_attempt_questions SET selected_option_id=:option WHERE attempt_id=:id AND id=:question")
            .param("option", input.optionId()).param("id", id).param("question", input.questionId()).update();
        jdbc.sql("UPDATE quiz_attempts SET version=version+1 WHERE id=:id AND user_id=:owner")
            .param("id", id).param("owner", owner).update();
    }
    public void submit(UUID owner, UUID id, int correct, int score, OffsetDateTime now) {
        jdbc.sql("""
            UPDATE quiz_attempts SET submitted_at=:now,correct_count=:correct,score=:score,version=version+1
            WHERE id=:id AND user_id=:owner AND submitted_at IS NULL
            """).param("id", id).param("owner", owner).param("correct", correct).param("score", score).param("now", now).update();
    }
    private static final String FILTER = " WHERE a.user_id=:owner AND (:status='' OR (:status='IN_PROGRESS' AND a.submitted_at IS NULL) OR (:status='SUBMITTED' AND a.submitted_at IS NOT NULL)) ";
    public List<Summary> list(UUID owner, String status, int page, int size) {
        return jdbc.sql(SUMMARY + FILTER + " ORDER BY a.created_at DESC,a.id LIMIT :size OFFSET :offset")
            .param("owner", owner).param("status", status).param("size", size).param("offset", (long) page * size).query(Summary.class).list();
    }
    public long count(UUID owner, String status) {
        return jdbc.sql("SELECT COUNT(*) FROM quiz_attempts a" + FILTER).param("owner", owner).param("status", status).query(Long.class).single();
    }
}
