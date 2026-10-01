package com.chineselearning.modules.admin.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@Service
public class AdminQuizService {
    public record Question(@NotNull @Pattern(regexp="CHOOSE_MEANING|CHOOSE_WORD") String type,
        @NotBlank @Size(max=500) String prompt,
        @NotNull @Size(min=4,max=4) List<@NotBlank @Size(max=500) String> options,
        @NotNull @Min(0) @Max(3) Integer correctIndex, @NotBlank @Size(max=1500) String explanation) {}
    public record Input(@NotBlank @Size(max=160) String title, @NotBlank @Size(max=500) String description,
        @NotNull @PositiveOrZero Long version, @NotNull @Size(max=100) List<@NotNull @Valid Question> questions) {}
    public record Publication(@NotNull @PositiveOrZero Long version, @NotNull Boolean published) {}
    public record Summary(long id,String title,String description,boolean published,long version,int questionCount) {}
    public record Detail(long id,String title,String description,boolean published,long version,List<Question> questions) {}
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    public AdminQuizService(JdbcClient jdbc,ObjectMapper mapper) { this.jdbc=jdbc;this.mapper=mapper; }
    public List<Summary> list() {
        return jdbc.sql("SELECT s.id,s.title,s.description,s.published,s.version,(SELECT count(*) FROM quiz_questions q WHERE q.set_id=s.id) AS question_count FROM quiz_sets s ORDER BY s.id DESC")
            .query(Summary.class).list();
    }
    @Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Detail get(long id) { return detail(id); }
    private Detail detail(long id) {
        var s=jdbc.sql("SELECT id,title,description,published,version FROM quiz_sets WHERE id=:id").param("id",id)
            .query((rs,n)->new Summary(rs.getLong("id"),rs.getString("title"),rs.getString("description"),rs.getBoolean("published"),rs.getLong("version"),0))
            .optional().orElseThrow(()->problem(HttpStatus.NOT_FOUND,"Không tìm thấy bộ đề."));
        var questions=jdbc.sql("SELECT type,prompt,options,correct_index,explanation FROM quiz_questions WHERE set_id=:id ORDER BY position").param("id",id)
            .query((rs,n)->new Question(rs.getString("type"),rs.getString("prompt"),Arrays.asList(mapper.readValue(rs.getString("options"),String[].class)),rs.getInt("correct_index"),rs.getString("explanation"))).list();
        return new Detail(s.id(),s.title(),s.description(),s.published(),s.version(),questions);
    }
    private Detail lock(long id,long version) {
        jdbc.sql("SELECT id FROM quiz_sets WHERE id=:id FOR UPDATE").param("id",id).query(Long.class).optional()
            .orElseThrow(()->problem(HttpStatus.NOT_FOUND,"Không tìm thấy bộ đề."));
        var d=detail(id);
        if(d.version()!=version)throw problem(HttpStatus.CONFLICT,"Bộ đề đã thay đổi. Hãy tải lại trước khi tiếp tục.");
        return d;
    }
    private void validate(Input input) {
        for(var q:input.questions())if(q.options().stream().map(String::strip).distinct().count()!=4)
            throw problem(HttpStatus.BAD_REQUEST,"Bốn lựa chọn trong mỗi câu phải khác nhau.");
    }
    @Transactional
    public Detail create(Input input) {
        validate(input);
        long id=jdbc.sql("INSERT INTO quiz_sets(title,description) VALUES(:title,:description) RETURNING id")
            .param("title",input.title().strip()).param("description",input.description().strip()).query(Long.class).single();
        writeQuestions(id,input.questions());return detail(id);
    }
    @Transactional
    public Detail update(long id,Input input) {
        var d=lock(id,input.version());
        if(d.published())throw problem(HttpStatus.CONFLICT,"Gỡ xuất bản trước khi sửa bộ đề.");
        validate(input);
        jdbc.sql("UPDATE quiz_sets SET title=:title,description=:description,version=version+1 WHERE id=:id")
            .param("title",input.title().strip()).param("description",input.description().strip()).param("id",id).update();
        jdbc.sql("DELETE FROM quiz_questions WHERE set_id=:id").param("id",id).update();
        writeQuestions(id,input.questions());return detail(id);
    }
    private void writeQuestions(long id,List<Question> questions) {
        for(int i=0;i<questions.size();i++) {
            var q=questions.get(i);
            jdbc.sql("INSERT INTO quiz_questions(set_id,position,type,prompt,options,correct_index,explanation) VALUES(:id,:position,:type,:prompt,CAST(:options AS jsonb),:correct,:explanation)")
                .param("id",id).param("position",i+1).param("type",q.type()).param("prompt",q.prompt().strip())
                .param("options",mapper.writeValueAsString(q.options().stream().map(String::strip).toList()))
                .param("correct",q.correctIndex()).param("explanation",q.explanation().strip()).update();
        }
    }
    @Transactional
    public Detail publish(long id,Publication input) {
        var d=lock(id,input.version());
        if(input.published()) {
            if(d.questions().size()<5)throw problem(HttpStatus.CONFLICT,"Cần ít nhất 5 câu hỏi để xuất bản.");
            validate(new Input(d.title(),d.description(),d.version(),d.questions()));
        }
        jdbc.sql("UPDATE quiz_sets SET published=:published,version=version+1 WHERE id=:id")
            .param("published",input.published()).param("id",id).update();return detail(id);
    }
    @Transactional
    public void delete(long id,long version) {
        if(lock(id,version).published())throw problem(HttpStatus.CONFLICT,"Chỉ có thể xóa bộ đề nháp.");
        jdbc.sql("DELETE FROM quiz_sets WHERE id=:id").param("id",id).update();
    }
    private ResponseStatusException problem(HttpStatus status,String message) { return new ResponseStatusException(status,message); }
}
