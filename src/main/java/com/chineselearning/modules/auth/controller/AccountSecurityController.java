package com.chineselearning.modules.auth.controller;

import com.chineselearning.modules.auth.service.*;
import com.chineselearning.common.security.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/account")
public class AccountSecurityController {
    private final AccountRecovery recovery;private final AccountSessions sessions;private final AuthThrottle throttle;
    public AccountSecurityController(AccountRecovery recovery,AccountSessions sessions,AuthThrottle throttle) { this.recovery=recovery;this.sessions=sessions;this.throttle=throttle; }
    public record Email(@NotBlank @jakarta.validation.constraints.Email @Size(max=254) String email) {}
    public record Reset(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,@NotBlank @Size(min=8,max=128) String password) {}
    public record Verify(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token) {}
    public record Status(boolean verified) {}
    @PostMapping("/forgot") public Map<String,String> forgot(@Valid @RequestBody Email body,HttpServletRequest req) {
        throttle.emailAction(body.email(),req.getRemoteAddr());recovery.request(body.email(),"RESET");return Map.of("message","Nếu tài khoản có thể khôi phục, hướng dẫn sẽ được gửi qua email.");
    }
    @PostMapping("/reset") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) public void reset(@Valid @RequestBody Reset body,HttpServletRequest req) { throttle.tokenAttempt(req.getRemoteAddr());recovery.consume(body.token(),"RESET",body.password()); }
    @PostMapping("/verify") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) public void verify(@Valid @RequestBody Verify body,HttpServletRequest req) { throttle.tokenAttempt(req.getRemoteAddr());recovery.consume(body.token(),"VERIFY",null); }
    @GetMapping("/status") public Status status(@AuthenticationPrincipal AccountPrincipal p) { return new Status(recovery.verified(p.accountId())); }
    @PostMapping("/verification-email") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) public void verification(@AuthenticationPrincipal AccountPrincipal p,HttpServletRequest req) { throttle.emailAction(p.getUsername(),req.getRemoteAddr());recovery.request(p.getUsername(),"VERIFY"); }
    @GetMapping("/sessions") public List<AccountSessions.Session> sessions(@AuthenticationPrincipal AccountPrincipal p,HttpServletRequest req) { return sessions.list(p.accountId(),req.getSession().getId()); }
    @DeleteMapping("/sessions/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT) public void revoke(@AuthenticationPrincipal AccountPrincipal p,@PathVariable UUID id) { sessions.revoke(p.accountId(),id); }
}
