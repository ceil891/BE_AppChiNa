package com.chineselearning.modules.auth.repository;

import java.time.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class AuthLimitRepository {
    private final JdbcClient jdbc;
    public AuthLimitRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    // A reservation commits independently, including when password validation later fails.
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void take(String key,int maximum,Duration window,Instant now) {
        var time=now.atOffset(ZoneOffset.UTC);
        jdbc.sql("DELETE FROM auth_request_limits WHERE bucket_key IN (SELECT bucket_key FROM auth_request_limits WHERE window_end<=:now ORDER BY window_end LIMIT 200)")
            .param("now",time).update();
        var accepted=jdbc.sql("""
            INSERT INTO auth_request_limits(bucket_key,window_end,request_count) VALUES (:key,:end,1)
            ON CONFLICT (bucket_key) DO UPDATE SET
                window_end=CASE WHEN auth_request_limits.window_end<=:now THEN :end ELSE auth_request_limits.window_end END,
                request_count=CASE WHEN auth_request_limits.window_end<=:now THEN 1 ELSE auth_request_limits.request_count+1 END
            WHERE auth_request_limits.window_end<=:now OR auth_request_limits.request_count<:maximum
            RETURNING window_end
            """).param("key",key).param("end",now.plus(window).atOffset(ZoneOffset.UTC))
            .param("now",time).param("maximum",maximum).query(OffsetDateTime.class).optional();
        if(accepted.isPresent())return;
        var end=jdbc.sql("SELECT window_end FROM auth_request_limits WHERE bucket_key=:key").param("key",key)
            .query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant).orElse(now.plusSeconds(1));
        long seconds=Math.max(1,(Duration.between(now,end).toMillis()+999)/1000);
        throw new RateLimitException(seconds);
    }
    private static final class RateLimitException extends ResponseStatusException {
        private final HttpHeaders headers=new HttpHeaders();
        RateLimitException(long seconds) {
            super(HttpStatus.TOO_MANY_REQUESTS,"Bạn đã thử quá nhiều lần. Vui lòng chờ rồi thử lại.");
            headers.set("Retry-After",Long.toString(seconds));
        }
        @Override public HttpHeaders getHeaders() { return headers; }
    }
}
