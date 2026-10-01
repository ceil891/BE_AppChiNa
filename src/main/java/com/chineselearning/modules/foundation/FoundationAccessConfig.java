package com.chineselearning.modules.foundation;

import com.chineselearning.common.security.AccountPrincipal;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
public class FoundationAccessConfig implements WebMvcConfigurer {
    private final FoundationService foundations;
    public FoundationAccessConfig(FoundationService foundations) { this.foundations=foundations; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                var auth=SecurityContextHolder.getContext().getAuthentication();
                var id=auth!=null && auth.getPrincipal() instanceof AccountPrincipal account?account.accountId():null;
                if(!foundations.completedCurriculum(id))throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Hoàn thành toàn bộ lộ trình nhập môn Pinyin trước khi học phần này.");
                return true;
            }
        }).addPathPatterns("/api/v1/courses", "/api/v1/courses/**", "/api/v1/lessons", "/api/v1/lessons/**",
            "/api/v1/vocabulary", "/api/v1/vocabulary/**", "/api/v1/learning-records", "/api/v1/learning-records/**",
            "/api/v1/flashcards", "/api/v1/flashcards/**", "/api/v1/quizzes/**", "/api/v1/skills/**", "/api/v1/ai/**");
    }
}
