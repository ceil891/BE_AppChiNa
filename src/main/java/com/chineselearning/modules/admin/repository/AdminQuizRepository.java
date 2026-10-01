package com.chineselearning.modules.admin.repository;

import com.chineselearning.modules.admin.dto.AdminQuizDtos.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@Repository
public class AdminQuizRepository {
    private final JdbcClient jdbc;private final ObjectMapper mapper;
    public AdminQuizRepository(JdbcClient jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    private static final String SUMMARY="""
        SELECT s.id,s.title,s.description,s.lesson_id,l.title AS lesson_title,s.published,s.version,
        (SELECT count(*) FROM quiz_questions q WHERE q.set_id=s.id) AS question_count
        FROM quiz_sets s LEFT JOIN lessons l ON l.id=s.lesson_id
        """;
    private static final String FILTER=" WHERE (:status='' OR s.published=:published) AND (s.title ILIKE :query ESCAPE '!' OR s.description ILIKE :query ESCAPE '!') ";
    private String pattern(String text){return "%"+text.strip().replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    public List<Summary> list(String query,String status,int page,int size){
        return jdbc.sql(SUMMARY+FILTER+" ORDER BY s.id DESC LIMIT :size OFFSET :offset")
            .param("query",pattern(query)).param("status",status).param("published",status.equals("PUBLISHED"))
            .param("size",size).param("offset",(long)page*size).query(Summary.class).list();
    }
    public long count(String query,String status){
        return jdbc.sql("SELECT count(*) FROM quiz_sets s"+FILTER).param("query",pattern(query)).param("status",status)
            .param("published",status.equals("PUBLISHED")).query(Long.class).single();
    }
    public Optional<Summary> get(long id){return jdbc.sql(SUMMARY+" WHERE s.id=:id").param("id",id).query(Summary.class).optional();}
    public boolean lock(long id){return jdbc.sql("SELECT id FROM quiz_sets WHERE id=:id FOR UPDATE").param("id",id).query(Long.class).optional().isPresent();}
    public boolean lessonExists(long id){return jdbc.sql("SELECT EXISTS(SELECT 1 FROM lessons WHERE id=:id)").param("id",id).query(Boolean.class).single();}
    public List<Question> questions(long id){
        return jdbc.sql("SELECT id,position,type,prompt,options,correct_index,explanation FROM quiz_questions WHERE set_id=:id ORDER BY position")
            .param("id",id).query((rs,i)->new Question(rs.getLong("id"),rs.getInt("position"),rs.getString("type"),rs.getString("prompt"),
                Arrays.asList(mapper.readValue(rs.getString("options"),String[].class)),rs.getInt("correct_index"),rs.getString("explanation"))).list();
    }
    public long create(Content input){
        return jdbc.sql("INSERT INTO quiz_sets(title,description,lesson_id) VALUES (:title,:description,:lesson) RETURNING id")
            .param("title",input.title().strip()).param("description",input.description().strip()).param("lesson",input.lessonId()).query(Long.class).single();
    }
    public void update(long id,Content input){
        jdbc.sql("UPDATE quiz_sets SET title=:title,description=:description,lesson_id=:lesson,version=version+1 WHERE id=:id")
            .param("id",id).param("title",input.title().strip()).param("description",input.description().strip()).param("lesson",input.lessonId()).update();
    }
    public void replaceQuestions(long id,List<QuestionInput> questions){
        jdbc.sql("DELETE FROM quiz_questions WHERE set_id=:id").param("id",id).update();
        for(int i=0;i<questions.size();i++){
            var q=questions.get(i);
            jdbc.sql("INSERT INTO quiz_questions(set_id,position,type,prompt,options,correct_index,explanation) VALUES (:id,:position,:type,:prompt,CAST(:options AS jsonb),:correct,:explanation)")
                .param("id",id).param("position",i+1).param("type",q.type()).param("prompt",q.prompt().strip())
                .param("options",mapper.writeValueAsString(q.options().stream().map(String::strip).toList()))
                .param("correct",q.correctIndex()).param("explanation",q.explanation().strip()).update();
        }
    }
    public void publication(long id,boolean published){jdbc.sql("UPDATE quiz_sets SET published=:published,version=version+1 WHERE id=:id").param("id",id).param("published",published).update();}
    public void delete(long id){jdbc.sql("DELETE FROM quiz_sets WHERE id=:id").param("id",id).update();}
}
