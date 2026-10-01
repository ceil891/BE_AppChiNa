package com.chineselearning.modules.auth.service;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

@Service
public class AccountRecovery {
    private final JdbcClient jdbc; private final PasswordEncoder encoder; private final Clock clock; private final AccountMail mail;
    private final SecureRandom random=new SecureRandom();
    public AccountRecovery(JdbcClient jdbc,PasswordEncoder encoder,Clock clock,AccountMail mail) { this.jdbc=jdbc;this.encoder=encoder;this.clock=clock;this.mail=mail; }
    public record Account(UUID id,long sessionVersion,boolean enabled,OffsetDateTime emailVerifiedAt) {}
    @Transactional public void request(String email,String purpose) {
        mail.available();
        var found=jdbc.sql("SELECT id,session_version,enabled,email_verified_at FROM users WHERE email=:email FOR UPDATE").param("email",email.strip().toLowerCase(Locale.ROOT)).query(Account.class).optional();
        if(found.isEmpty() || !found.get().enabled() || purpose.equals("VERIFY") && found.get().emailVerifiedAt()!=null)return;
        var user=found.get();byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);var now=OffsetDateTime.now(clock);
        jdbc.sql("DELETE FROM account_tokens WHERE user_id=:owner AND purpose=:purpose").param("owner",user.id()).param("purpose",purpose).update();
        jdbc.sql("INSERT INTO account_tokens(token_hash,user_id,purpose,session_version,expires_at,created_at) VALUES(:hash,:owner,:purpose,:version,:expiry,:now)")
            .param("hash",AccountSessions.hash(token)).param("owner",user.id()).param("purpose",purpose).param("version",user.sessionVersion())
            .param("expiry",purpose.equals("RESET")?now.plusMinutes(30):now.plusHours(24)).param("now",now).update();
        try { mail.send(email.strip().toLowerCase(Locale.ROOT),purpose,token); }
        catch(ResponseStatusException ex) {
            if(!purpose.equals("RESET"))throw ex;
            // Recovery responses must not disclose whether the account exists when delivery fails.
            org.slf4j.LoggerFactory.getLogger(AccountRecovery.class).warn("Password recovery email delivery failed; inspect mail configuration.");
        }
    }
    public record Token(UUID userId,long sessionVersion,OffsetDateTime expiresAt) {}
    @Transactional public void consume(String value,String purpose,String password) {
        String hash=AccountSessions.hash(value);
        var first=jdbc.sql("SELECT user_id,session_version,expires_at FROM account_tokens WHERE token_hash=:hash AND purpose=:purpose").param("hash",hash).param("purpose",purpose).query(Token.class).optional().orElseThrow(this::invalid);
        var user=jdbc.sql("SELECT id,session_version,enabled,email_verified_at FROM users WHERE id=:id FOR UPDATE").param("id",first.userId()).query(Account.class).optional().orElseThrow(this::invalid);
        var token=jdbc.sql("SELECT user_id,session_version,expires_at FROM account_tokens WHERE token_hash=:hash AND purpose=:purpose FOR UPDATE").param("hash",hash).param("purpose",purpose).query(Token.class).optional().orElseThrow(this::invalid);
        if(!user.enabled() || token.sessionVersion()!=user.sessionVersion() || !token.expiresAt().isAfter(OffsetDateTime.now(clock)))throw invalid();
        if(purpose.equals("RESET")) {
            jdbc.sql("UPDATE users SET password_hash=:password,session_version=session_version+1 WHERE id=:id").param("password",encoder.encode(password)).param("id",user.id()).update();
            jdbc.sql("DELETE FROM account_tokens WHERE user_id=:id").param("id",user.id()).update();
        } else {
            jdbc.sql("UPDATE users SET email_verified_at=:now WHERE id=:id").param("now",OffsetDateTime.now(clock)).param("id",user.id()).update();
            jdbc.sql("DELETE FROM account_tokens WHERE token_hash=:hash").param("hash",hash).update();
        }
    }
    public boolean verified(UUID owner) { return jdbc.sql("SELECT email_verified_at IS NOT NULL FROM users WHERE id=:id").param("id",owner).query(Boolean.class).single(); }
    private ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Liên kết không hợp lệ hoặc đã hết hạn. Hãy yêu cầu email mới."); }
}
