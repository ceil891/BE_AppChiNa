package com.chineselearning.modules.ai.repository;

import com.chineselearning.modules.ai.dto.AiDtos.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;

@Repository
public class AiRepository {
    public record Stored(UUID id,Long lessonId,String title,String contextText,String promptVersion,OffsetDateTime createdAt,OffsetDateTime updatedAt,String mode) {
        public Conversation view() { return new Conversation(id,lessonId,title,promptVersion,createdAt,updatedAt,mode); }
    }
    private final JdbcClient jdbc;
    public AiRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public void lockOwner(UUID owner) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(CAST(:owner AS text),712))").param("owner",owner)
            .query((org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs -> rs.next());
    }
    private static final String CONVERSATION="SELECT id,lesson_id,title,context_text,prompt_version,created_at,updated_at,mode FROM ai_conversations ";
    public Optional<Stored> find(UUID owner,UUID id) {
        return jdbc.sql(CONVERSATION+"WHERE user_id=:owner AND id=:id").param("owner",owner).param("id",id).query(Stored.class).optional();
    }
    public Optional<Stored> createdBy(UUID owner,UUID request) {
        return jdbc.sql(CONVERSATION+"WHERE user_id=:owner AND request_id=:request").param("owner",owner).param("request",request).query(Stored.class).optional();
    }
    public void create(UUID owner,UUID id,Start input,String title,String context,String version,OffsetDateTime now) {
        jdbc.sql("""
            INSERT INTO ai_conversations(id,user_id,request_id,lesson_id,title,context_text,prompt_version,created_at,updated_at,mode)
            VALUES (:id,:owner,:request,:lesson,:title,:context,:version,:now,:now,:mode)
            """).param("id",id).param("owner",owner).param("request",input.requestId()).param("lesson",input.lessonId())
            .param("title",title).param("context",context).param("version",version).param("now",now).param("mode",input.mode()).update();
    }
    public void rename(UUID owner,UUID id,String title,OffsetDateTime now) {
        jdbc.sql("UPDATE ai_conversations SET title=:title,updated_at=:now WHERE id=:id AND user_id=:owner")
            .param("owner",owner).param("id",id).param("title",title).param("now",now).update();
    }
    public long count(UUID owner) { return jdbc.sql("SELECT COUNT(*) FROM ai_conversations WHERE user_id=:owner").param("owner",owner).query(Long.class).single(); }
    public List<Conversation> list(UUID owner,int page,int size) {
        return jdbc.sql(CONVERSATION+"WHERE user_id=:owner ORDER BY updated_at DESC,id LIMIT :size OFFSET :offset")
            .param("owner",owner).param("size",size).param("offset",(long)page*size).query(Stored.class).list().stream().map(Stored::view).toList();
    }
    public void delete(UUID owner,UUID id) { jdbc.sql("DELETE FROM ai_conversations WHERE user_id=:owner AND id=:id").param("owner",owner).param("id",id).update(); }
    private static final String TURN="SELECT id,request_id,retry_of,question,answer,status,error_code,model,created_at,finished_at,expires_at,input_tokens,output_tokens,total_tokens FROM ai_turns ";
    public List<Turn> turns(UUID owner,UUID conversation) {
        return jdbc.sql(TURN+"WHERE user_id=:owner AND conversation_id=:id ORDER BY sequence").param("owner",owner).param("id",conversation).query(Turn.class).list();
    }
    public Optional<Turn> request(UUID owner,UUID request) {
        return jdbc.sql(TURN+"WHERE user_id=:owner AND request_id=:request").param("owner",owner).param("request",request).query(Turn.class).optional();
    }
    public boolean inConversation(UUID owner,UUID turn,UUID conversation) {
        return jdbc.sql("SELECT COUNT(*) FROM ai_turns WHERE user_id=:owner AND id=:turn AND conversation_id=:id")
            .param("owner",owner).param("turn",turn).param("id",conversation).query(Integer.class).single()==1;
    }
    public void expire(UUID owner,OffsetDateTime now) {
        jdbc.sql("UPDATE ai_turns SET status='FAILED',error_code='TIMEOUT',finished_at=:now WHERE user_id=:owner AND status='PENDING' AND expires_at<=:now")
            .param("owner",owner).param("now",now).update();
    }
    public boolean pending(UUID owner) { return jdbc.sql("SELECT COUNT(*) FROM ai_turns WHERE user_id=:owner AND status='PENDING'").param("owner",owner).query(Integer.class).single()>0; }
    public int used(UUID owner,LocalDate date) {
        return jdbc.sql("SELECT attempts FROM ai_daily_usage WHERE user_id=:owner AND study_date=:date").param("owner",owner).param("date",date).query(Integer.class).optional().orElse(0);
    }
    public int globalUsed(LocalDate date) {
        return jdbc.sql("SELECT attempts FROM ai_global_usage WHERE study_date=:date").param("date",date).query(Integer.class).optional().orElse(0);
    }
    public void lockBudget(UUID owner,LocalDate date) {
        jdbc.sql("INSERT INTO ai_global_usage(study_date) VALUES (:date) ON CONFLICT DO NOTHING").param("date",date).update();
        jdbc.sql("SELECT study_date FROM ai_global_usage WHERE study_date=:date FOR UPDATE").param("date",date).query(LocalDate.class).single();
        jdbc.sql("INSERT INTO ai_daily_usage(user_id,study_date) VALUES (:owner,:date) ON CONFLICT DO NOTHING").param("owner",owner).param("date",date).update();
    }
    public void charge(UUID owner,LocalDate date,int tokens) {
        jdbc.sql("UPDATE ai_global_usage SET attempts=attempts+1,reserved_tokens=reserved_tokens+:tokens WHERE study_date=:date")
            .param("date",date).param("tokens",tokens).update();
        jdbc.sql("UPDATE ai_daily_usage SET attempts=attempts+1,reserved_tokens=reserved_tokens+:tokens WHERE user_id=:owner AND study_date=:date")
            .param("owner",owner).param("date",date).param("tokens",tokens).update();
    }
    public void begin(UUID owner,UUID conversation,UUID id,Send input,String model,OffsetDateTime now) {
        jdbc.sql("""
            INSERT INTO ai_turns(id,conversation_id,user_id,request_id,retry_of,question,status,model,created_at,expires_at)
            VALUES (:id,:conversation,:owner,:request,:retry,:question,'PENDING',:model,:now,:expires)
            """).param("id",id).param("conversation",conversation).param("owner",owner).param("request",input.requestId())
            .param("retry",input.retryOf()).param("question",input.question().strip()).param("model",model).param("now",now).param("expires",now.plusSeconds(60)).update();
        jdbc.sql("UPDATE ai_conversations SET updated_at=:now WHERE id=:id AND user_id=:owner").param("id",conversation).param("owner",owner).param("now",now).update();
    }
    public void finish(UUID owner,UUID id,String answer,String error,Integer in,Integer out,Integer total,OffsetDateTime now) {
        jdbc.sql("""
            UPDATE ai_turns SET status=:status,answer=:answer,error_code=:error,finished_at=:now,input_tokens=:input,output_tokens=:output,total_tokens=:total
            WHERE user_id=:owner AND id=:id AND status='PENDING' AND expires_at>:now
            """).param("status",error==null?"SUCCEEDED":"FAILED").param("answer",answer).param("error",error).param("now",now)
            .param("input",in).param("output",out).param("total",total).param("owner",owner).param("id",id).update();
    }
}
