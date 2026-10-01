package com.chineselearning.modules.admin.repository;

import com.chineselearning.modules.admin.dto.AdminUserRequests.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class AdminUserRepository {
    public record ManagedUser(UUID id, String email, String displayName, String role, boolean enabled,
                              OffsetDateTime createdAt, long learningRecordCount) {}
    private final JdbcClient jdbc;
    public AdminUserRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    private static final String FIELDS="u.id,u.email,u.display_name,u.role,u.enabled,u.created_at,(SELECT COUNT(*) FROM learning_records r WHERE r.user_id=u.id) AS learning_record_count";
    private static final String FILTER=" FROM users u WHERE (strpos(lower(u.email),lower(:q))>0 OR strpos(lower(u.display_name),lower(:q))>0) AND (:role='' OR u.role=:role) AND (:status='' OR (:status='active' AND u.enabled) OR (:status='locked' AND NOT u.enabled))";
    public List<ManagedUser> list(String q,String role,String status,int page,int size) {
        return jdbc.sql("SELECT "+FIELDS+FILTER+" ORDER BY u.created_at DESC,u.id LIMIT :size OFFSET :offset")
            .param("q",q).param("role",role).param("status",status).param("size",size).param("offset",(long)page*size).query(ManagedUser.class).list();
    }
    public long count(String q,String role,String status) {
        return jdbc.sql("SELECT COUNT(*)"+FILTER).param("q",q).param("role",role).param("status",status).query(Long.class).single();
    }
    public Optional<ManagedUser> find(UUID id) {
        return jdbc.sql("SELECT "+FIELDS+" FROM users u WHERE u.id=:id").param("id",id).query(ManagedUser.class).optional();
    }
    public long activeAdmins() { return jdbc.sql("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND enabled").query(Long.class).single(); }
    public UUID create(CreateUser input,String hash) {
        var id=UUID.randomUUID();
        jdbc.sql("INSERT INTO users(id,email,display_name,password_hash,role) VALUES (:id,:email,:name,:hash,:role)")
            .param("id",id).param("email",input.email()).param("name",input.displayName().strip()).param("hash",hash).param("role",input.role()).update();
        return id;
    }
    public void update(UUID id,UpdateUser input) {
        jdbc.sql("""
            UPDATE users SET display_name=:name, role=:role, enabled=:enabled,
            session_version=session_version + CASE WHEN role<>:role OR enabled<>:enabled THEN 1 ELSE 0 END
            WHERE id=:id
            """).param("id",id).param("name",input.displayName().strip()).param("role",input.role()).param("enabled",input.enabled()).update();
    }
    public void resetPassword(UUID id,String hash) {
        jdbc.sql("UPDATE users SET password_hash=:hash,session_version=session_version+1 WHERE id=:id").param("id",id).param("hash",hash).update();
    }
    public void delete(UUID id) { jdbc.sql("DELETE FROM users WHERE id=:id").param("id",id).update(); }
}
