package com.chineselearning.modules.user.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class UserRepository {
    public record Account(UUID id, String email, String passwordHash, String displayName, String role, boolean enabled, long sessionVersion, String timeZone) {}
    private final JdbcClient jdbc;
    public UserRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public Optional<Account> findByEmail(String email) {
        return jdbc.sql("SELECT id, email, password_hash, display_name, role, enabled, session_version, time_zone FROM users WHERE email = :email")
            .param("email", email).query(Account.class).optional();
    }
    public Account create(String email, String hash, String name) {
        return jdbc.sql("INSERT INTO users(id,email,password_hash,display_name) VALUES (:id,:email,:hash,:name) RETURNING id,email,password_hash,display_name,role,enabled,session_version,time_zone")
            .param("id", UUID.randomUUID()).param("email", email).param("hash", hash).param("name", name)
            .query(Account.class).single();
    }
    public void updateName(UUID id, String name) {
        jdbc.sql("UPDATE users SET display_name = :name WHERE id = :id").param("name", name).param("id", id).update();
    }
    public boolean changePassword(UUID id,long expectedVersion,String hash) {
        return jdbc.sql("UPDATE users SET password_hash=:hash,session_version=session_version+1 WHERE id=:id AND session_version=:version AND enabled=true")
            .param("id",id).param("version",expectedVersion).param("hash",hash).update()==1;
    }
}
