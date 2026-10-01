package com.chineselearning.modules.auth.service;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service
public class AccountSessions {
    private final JdbcClient jdbc; private final Clock clock;
    private final com.chineselearning.modules.settings.GeneralSettings settings;
    public AccountSessions(JdbcClient jdbc,Clock clock,com.chineselearning.modules.settings.GeneralSettings settings) { this.jdbc=jdbc;this.clock=clock;this.settings=settings; }
    public int idleSeconds() { return settings.snapshot().idleMinutes()*60; }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @org.springframework.transaction.annotation.Transactional
    public void add(UUID owner,long version,String session,String device) {
        var now=OffsetDateTime.now(clock);
        jdbc.sql("SELECT id FROM general_settings WHERE id=1 FOR UPDATE").query(Integer.class).single();
        jdbc.sql("INSERT INTO account_sessions(id,user_id,session_hash,session_version,device,created_at,last_seen_at) VALUES(:id,:owner,:hash,:version,:device,:now,:now)")
            .param("id",UUID.randomUUID()).param("owner",owner).param("hash",hash(session)).param("version",version)
            .param("device",device==null?"Trình duyệt":device.substring(0,Math.min(device.length(),300))).param("now",now).update();
    }
    private static final String ACTIVE="""
        AND s.last_seen_at>:idle AND s.created_at>CASE WHEN EXISTS
        (SELECT 1 FROM users u JOIN role_permissions rp ON rp.role_code=u.role WHERE u.id=s.user_id)
        THEN :adminAge ELSE :userAge END
        """;
    public boolean touch(UUID owner,String session) {
        var policy=settings.snapshot();
        var now=OffsetDateTime.now(clock);
        return jdbc.sql("UPDATE account_sessions s SET last_seen_at=:now WHERE user_id=:owner AND session_hash=:hash AND NOT revoked "+ACTIVE)
            .param("now",now).param("idle",now.minusMinutes(policy.idleMinutes())).param("adminAge",now.minusMinutes(policy.managerMinutes())).param("userAge",now.minusMinutes(policy.learnerMinutes())).param("owner",owner).param("hash",hash(session)).update()==1;
    }
    public record Session(UUID id,String device,OffsetDateTime createdAt,OffsetDateTime lastSeenAt,boolean current) {}
    public List<Session> list(UUID owner,String current) {
        var policy=settings.snapshot();
        return jdbc.sql("""
            SELECT s.id,s.device,s.created_at,s.last_seen_at,s.session_hash=:hash AS current
            FROM account_sessions s JOIN users u ON u.id=s.user_id
            WHERE s.user_id=:owner AND NOT s.revoked AND s.session_version=u.session_version AND s.last_seen_at>:since
            """+ACTIVE+"""
            ORDER BY s.last_seen_at DESC LIMIT 100
            """).param("owner",owner).param("hash",hash(current)).param("since",OffsetDateTime.now(clock).minusMinutes(policy.idleMinutes())).param("idle",OffsetDateTime.now(clock).minusMinutes(policy.idleMinutes())).param("adminAge",OffsetDateTime.now(clock).minusMinutes(policy.managerMinutes())).param("userAge",OffsetDateTime.now(clock).minusMinutes(policy.learnerMinutes())).query(Session.class).list();
    }
    public void revoke(UUID owner,UUID id) { jdbc.sql("UPDATE account_sessions SET revoked=true WHERE user_id=:owner AND id=:id").param("owner",owner).param("id",id).update(); }
    public void logout(String session) { jdbc.sql("UPDATE account_sessions SET revoked=true WHERE session_hash=:hash").param("hash",hash(session)).update(); }
}
