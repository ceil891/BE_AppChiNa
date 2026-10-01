package com.chineselearning.common.security;

import com.chineselearning.common.exception.ApiProblems;
import com.chineselearning.modules.user.repository.UserRepository;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.authentication.session.*;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import java.util.Arrays;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8(); }
    @Bean SecurityContextRepository contexts() { return new HttpSessionSecurityContextRepository(); }
    @Bean CsrfTokenRepository csrfTokens() { return new HttpSessionCsrfTokenRepository(); }
    @Bean SessionAuthenticationStrategy loginStrategy(CsrfTokenRepository csrfTokens) {
        return new CompositeSessionAuthenticationStrategy(List.of(
            new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrfTokens)));
    }
    @Bean AuthenticationManager authenticationManager(UserRepository users, PasswordEncoder encoder) {
        UserDetailsService lookup = email -> users.findByEmail(email)
            .map(AccountPrincipal::new)
            .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
        var provider = new DaoAuthenticationProvider(lookup);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
    @Bean SecurityFilterChain security(HttpSecurity http, SecurityContextRepository contexts,
            CsrfTokenRepository csrfTokens, ApiProblems problems, UserRepository users, PermissionService access,com.chineselearning.modules.settings.GeneralSettings settings,com.chineselearning.modules.auth.service.AccountSessions sessions) throws Exception {
        return http
            .cors(c -> {})
            .headers(h -> h
                .contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; media-src 'self' blob:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'"))
                .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .permissionsPolicyHeader(p -> p.policy("camera=(), geolocation=(), microphone=(self), payment=()"))
                .httpStrictTransportSecurity(s -> s.includeSubDomains(false).maxAgeInSeconds(31536000)))
            .addFilterBefore(new ApiRequestGuard(problems,settings),org.springframework.security.web.csrf.CsrfFilter.class)
            .securityContext(c -> c.securityContextRepository(contexts).requireExplicitSave(true))
            .addFilterAfter(new CurrentAccountFilter(users,sessions), SecurityContextHolderFilter.class)
            .csrf(c -> c.csrfTokenRepository(csrfTokens))
            .requestCache(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(a -> a
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/admin/roles/options").access(permission(access,"users.read"))
                .requestMatchers(HttpMethod.GET,"/api/v1/admin/roles","/api/v1/admin/roles/*").access(permission(access,"roles.read"))
                .requestMatchers(HttpMethod.POST,"/api/v1/admin/roles").access(permission(access,"roles.create"))
                .requestMatchers(HttpMethod.PUT,"/api/v1/admin/roles/*").access(permission(access,"roles.update"))
                .requestMatchers(HttpMethod.DELETE,"/api/v1/admin/roles/*").access(permission(access,"roles.delete"))
                .requestMatchers(HttpMethod.GET,"/api/v1/admin/users","/api/v1/admin/users/*").access(permission(access,"users.read"))
                .requestMatchers(HttpMethod.GET,"/api/v1/admin/users/*/skills","/api/v1/admin/users/*/skills/**").access(permission(access,"users.skills.read"))
                .requestMatchers(HttpMethod.POST,"/api/v1/admin/users").access(permission(access,"users.create"))
                .requestMatchers(HttpMethod.PUT,"/api/v1/admin/users/*").access(permission(access,"users.update","users.assign_role","users.status"))
                .requestMatchers(HttpMethod.PATCH,"/api/v1/admin/users/*/status").access(permission(access,"users.status"))
                .requestMatchers(HttpMethod.POST,"/api/v1/admin/users/*/password").access(permission(access,"users.password"))
                .requestMatchers(HttpMethod.DELETE,"/api/v1/admin/users/*").access(permission(access,"users.delete"))
                .requestMatchers(HttpMethod.GET,"/api/v1/admin/vocabulary","/api/v1/admin/vocabulary/*").access(permission(access,"vocabulary.read"))
                .requestMatchers(HttpMethod.POST,"/api/v1/admin/vocabulary").access(permission(access,"vocabulary.create"))
                .requestMatchers(HttpMethod.PUT,"/api/v1/admin/vocabulary/*").access(permission(access,"vocabulary.update"))
                .requestMatchers(HttpMethod.DELETE,"/api/v1/admin/vocabulary/*").access(permission(access,"vocabulary.delete"))
                .requestMatchers(HttpMethod.GET,"/api/v1/admin/lessons","/api/v1/admin/lessons/*").access(permission(access,"lessons.read"))
                .requestMatchers(HttpMethod.POST,"/api/v1/admin/lessons").access(permission(access,"lessons.create"))
                .requestMatchers(HttpMethod.PUT,"/api/v1/admin/lessons/*").access(permission(access,"lessons.update"))
                .requestMatchers(HttpMethod.DELETE,"/api/v1/admin/lessons/*").access(permission(access,"lessons.delete"))
                .requestMatchers(HttpMethod.GET,"/api/v1/admin/quizzes","/api/v1/admin/quizzes/*").access(permission(access,"quizzes.read"))
                .requestMatchers(HttpMethod.POST,"/api/v1/admin/quizzes").access(permission(access,"quizzes.create"))
                .requestMatchers(HttpMethod.PUT,"/api/v1/admin/quizzes/*").access(permission(access,"quizzes.update"))
                .requestMatchers(HttpMethod.PATCH,"/api/v1/admin/quizzes/*/publication").access(permission(access,"quizzes.publish"))
                .requestMatchers(HttpMethod.DELETE,"/api/v1/admin/quizzes/*").access(permission(access,"quizzes.delete"))
                .requestMatchers(HttpMethod.GET,"/api/v1/admin/skills/**").access(permission(access,"skills.read"))
                .requestMatchers(HttpMethod.POST,"/api/v1/admin/skills/*").access(permission(access,"skills.create"))
                .requestMatchers(HttpMethod.PUT,"/api/v1/admin/skills/*/*").access(permission(access,"skills.update"))
                .requestMatchers(HttpMethod.PATCH,"/api/v1/admin/skills/*/*/publication").access(permission(access,"skills.publish"))
                .requestMatchers(HttpMethod.PATCH,"/api/v1/admin/lessons/*/publication").access(permission(access,"lessons.update"))
                .requestMatchers(HttpMethod.GET,"/api/v1/admin/foundations","/api/v1/admin/foundations/*").access(permission(access,"foundations.read"))
                .requestMatchers(HttpMethod.POST,"/api/v1/admin/foundations").access(permission(access,"foundations.create"))
                .requestMatchers(HttpMethod.PUT,"/api/v1/admin/foundations/*").access(permission(access,"foundations.update"))
                .requestMatchers(HttpMethod.PATCH,"/api/v1/admin/foundations/*/publication").access(permission(access,"foundations.publish"))
                .requestMatchers(HttpMethod.GET,"/api/v1/admin/learner-progress","/api/v1/admin/learner-progress/*").access(permission(access,"users.progress.read"))
                .requestMatchers(HttpMethod.GET,"/api/v1/admin/courses","/api/v1/admin/courses/*").access(permission(access,"courses.read"))
                .requestMatchers(HttpMethod.POST,"/api/v1/admin/courses").access(permission(access,"courses.create"))
                .requestMatchers(HttpMethod.PUT,"/api/v1/admin/courses/*").access(permission(access,"courses.update"))
                .requestMatchers(HttpMethod.PATCH,"/api/v1/admin/courses/*/publication").access(permission(access,"courses.publish"))
                .requestMatchers(HttpMethod.DELETE,"/api/v1/admin/courses/*").access(permission(access,"courses.delete"))
                .requestMatchers(HttpMethod.GET,"/api/v1/admin/ai-settings").access(permission(access,"ai.read"))
                .requestMatchers(HttpMethod.PUT,"/api/v1/admin/ai-settings").access(permission(access,"ai.update"))
                .requestMatchers(HttpMethod.GET,"/api/v1/admin/general-settings").access(permission(access,"settings.read"))
                .requestMatchers(HttpMethod.PUT,"/api/v1/admin/general-settings").access(permission(access,"settings.update"))
                .requestMatchers("/api/v1/admin/**").denyAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/courses","/api/v1/courses/*","/courses","/courses/**").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/v1/account/forgot","/api/v1/account/reset","/api/v1/account/verify").permitAll()
                .requestMatchers("/api/v1/account/**").authenticated()
                .requestMatchers(HttpMethod.GET,"/account/**").permitAll()
                .requestMatchers(HttpMethod.GET,"/home","/practice","/foundations","/foundations/**","/audio/foundations/**").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/roadmap").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/foundations","/api/v1/foundations/*").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/v1/foundations/*/check").permitAll()
                .requestMatchers("/api/v1/foundation-progress","/api/v1/foundation-progress/*").authenticated()
                .requestMatchers(HttpMethod.GET,"/","/index.html","/favicon.svg","/assets/**","/audio/skills/**",
                    "/login","/register","/lessons","/lessons/**","/vocabulary","/vocabulary/**",
                    "/learning","/profile","/progress","/progress/**","/flashcards","/flashcards/**",
                    "/skills","/skills/**","/quizzes","/quizzes/**","/ai","/ai/**","/admin","/admin/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf", "/api/v1/lessons", "/api/v1/lessons/*",
                    "/api/v1/vocabulary", "/api/v1/vocabulary/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/v1/auth/password").authenticated()
                .requestMatchers("/api/v1/auth/me", "/api/v1/profile", "/api/v1/learning-records", "/api/v1/learning-records/*").authenticated()
                .requestMatchers("/api/v1/flashcards", "/api/v1/flashcards/**").authenticated()
                .requestMatchers("/api/v1/quizzes", "/api/v1/quizzes/**").authenticated()
                .requestMatchers("/api/v1/skills/**").authenticated()
                .requestMatchers(HttpMethod.GET,"/api/v1/progress","/api/v1/progress/events").authenticated()
                .requestMatchers(HttpMethod.GET,"/api/v1/ai/status","/api/v1/ai/conversations","/api/v1/ai/conversations/*").authenticated()
                .requestMatchers(HttpMethod.POST,"/api/v1/ai/conversations","/api/v1/ai/conversations/*/turns").authenticated()
                .requestMatchers(HttpMethod.DELETE,"/api/v1/ai/conversations/*").authenticated()
                .requestMatchers(HttpMethod.PATCH,"/api/v1/ai/conversations/*").authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> problems.write(req, res, 401, "Vui lòng đăng nhập."))
                .accessDeniedHandler((req, res, ex) -> problems.write(req, res, 403, "Yêu cầu bị từ chối hoặc mã CSRF không hợp lệ.")))
            .logout(l -> l.addLogoutHandler((req,res,auth) -> { var s=req.getSession(false); if(s!=null)sessions.logout(s.getId()); }).logoutUrl("/api/v1/auth/logout").invalidateHttpSession(true)
                .clearAuthentication(true).deleteCookies("JSESSIONID")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
            .build();
    }
    private AuthorizationManager<RequestAuthorizationContext> permission(PermissionService access,String... codes) {
        return (authentication,context)->new AuthorizationDecision(Arrays.stream(codes).anyMatch(code->access.has(authentication.get().getName(),code)));
    }
}

