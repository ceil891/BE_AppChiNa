package com.chineselearning.modules.auth.controller;

import com.chineselearning.modules.auth.dto.AuthRequests.*;
import com.chineselearning.modules.auth.service.AuthService;
import com.chineselearning.modules.auth.service.AuthThrottle;
import com.chineselearning.common.security.AccountPrincipal;
import com.chineselearning.modules.user.service.UserService;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public record CsrfResponse(String token, String headerName) {}
    private final AuthService auth;
    private final UserService users;
    private final AuthenticationManager manager;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy loginStrategy;
    private final AuthThrottle throttle;
    private final com.chineselearning.modules.auth.service.AccountSessions sessions;
    public AuthController(AuthService auth, UserService users, AuthenticationManager manager,
            SecurityContextRepository contexts, SessionAuthenticationStrategy loginStrategy,AuthThrottle throttle,com.chineselearning.modules.auth.service.AccountSessions sessions) {
        this.auth=auth; this.users=users; this.manager=manager; this.contexts=contexts; this.loginStrategy=loginStrategy;
        this.throttle=throttle;this.sessions=sessions;
    }
    @GetMapping("/csrf") public CsrfResponse csrf(CsrfToken token) { return new CsrfResponse(token.getToken(), token.getHeaderName()); }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public UserService.Profile register(@Valid @RequestBody Register body,HttpServletRequest request) {
        throttle.register(body.email(),request.getRemoteAddr());return auth.register(body);
    }
    @PostMapping("/login")
    public UserService.Profile login(@Valid @RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
        throttle.login(body.email(),request.getRemoteAddr());
        var authentication = manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.email(), body.password()));
        var previousSession=request.getSession(false);
        if(previousSession!=null)sessions.logout(previousSession.getId());
        loginStrategy.onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        var principal=(AccountPrincipal)authentication.getPrincipal();
        request.getSession(true).setMaxInactiveInterval(sessions.idleSeconds());
        sessions.add(principal.accountId(),principal.sessionVersion(),request.getSession(true).getId(),request.getHeader("User-Agent"));
        contexts.saveContext(context, request, response);
        return users.current(authentication.getName());
    }
    @PostMapping("/password") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@AuthenticationPrincipal AccountPrincipal principal,@Valid @RequestBody ChangePassword body,
            HttpServletRequest request,HttpServletResponse response) {
        throttle.password(principal.accountId());
        auth.changePassword(principal,body);
        new SecurityContextLogoutHandler().logout(request,response,SecurityContextHolder.getContext().getAuthentication());
    }
    @GetMapping("/me") public UserService.Profile me(Principal principal) { return users.current(principal.getName()); }
}
