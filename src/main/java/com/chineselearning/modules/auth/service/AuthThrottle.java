package com.chineselearning.modules.auth.service;

import com.chineselearning.modules.auth.repository.AuthLimitRepository;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AuthThrottle {
    private final AuthLimitRepository limits;
    private final Clock clock;
    private final int globalMax,loginIpMax,loginEmailMax,registerIpMax;
    public AuthThrottle(AuthLimitRepository limits,Clock clock,
        @Value("${auth.limits.global-per-minute:1000}") int globalMax,
        @Value("${auth.limits.login-ip-per-minute:60}") int loginIpMax,
        @Value("${auth.limits.login-email-per-quarter-hour:10}") int loginEmailMax,
        @Value("${auth.limits.register-ip-per-hour:20}") int registerIpMax) {
        if(globalMax<1 || loginIpMax<1 || loginEmailMax<1 || registerIpMax<1)throw new IllegalArgumentException("Auth limits must be positive.");
        this.limits=limits;this.clock=clock;this.globalMax=globalMax;this.loginIpMax=loginIpMax;this.loginEmailMax=loginEmailMax;this.registerIpMax=registerIpMax;
    }
    public void login(String email,String address) {
        global();take("login-ip",address,loginIpMax,Duration.ofMinutes(1));
        take("login-email",email.strip().toLowerCase(Locale.ROOT),loginEmailMax,Duration.ofMinutes(15));
    }
    public void register(String email,String address) {
        global();take("register-ip",address,registerIpMax,Duration.ofHours(1));
        take("register-email",email.strip().toLowerCase(Locale.ROOT),3,Duration.ofHours(1));
    }
    public void emailAction(String email,String address) { global();take("email-action-ip",address,20,Duration.ofHours(1));take("email-action-address",email.strip().toLowerCase(Locale.ROOT),3,Duration.ofHours(1)); }
    public void tokenAttempt(String address) { global();take("token-ip",address,30,Duration.ofMinutes(15)); }
    public void password(UUID accountId) { global();take("password",accountId.toString(),5,Duration.ofMinutes(15)); }
    private void global() { take("global","all",globalMax,Duration.ofMinutes(1)); }
    private void take(String kind,String value,int maximum,Duration window) {
        limits.take(kind+":"+digest(value),maximum,window,clock.instant());
    }
    private String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
