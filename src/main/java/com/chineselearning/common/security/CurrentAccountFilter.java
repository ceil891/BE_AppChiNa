package com.chineselearning.common.security;

import com.chineselearning.modules.user.repository.UserRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;

// Registered only inside Spring Security, after the context is read and before CSRF.
public final class CurrentAccountFilter extends OncePerRequestFilter {
    private final UserRepository users;
    private final com.chineselearning.modules.auth.service.AccountSessions sessions;
    public CurrentAccountFilter(UserRepository users,com.chineselearning.modules.auth.service.AccountSessions sessions) { this.users = users; this.sessions=sessions; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AccountPrincipal principal) {
            boolean valid = users.findByEmail(principal.getUsername())
                .map(account -> account.enabled() && account.id().equals(principal.accountId())
                    && account.sessionVersion() == principal.sessionVersion()).orElse(false);
            var active=request.getSession(false);
            valid=valid && active!=null && sessions.touch(principal.accountId(),active.getId());
            if (!valid) {
                var session = request.getSession(false);
                if (session != null) {
                    try { session.invalidate(); }
                    catch (IllegalStateException ignored) { /* Another request already invalidated the same session. */ }
                }
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
