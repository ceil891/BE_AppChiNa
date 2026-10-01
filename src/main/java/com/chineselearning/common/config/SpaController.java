package com.chineselearning.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@ConditionalOnResource(resources="classpath:/static/index.html")
public class SpaController {
    // Only application UI paths fall back to React. API and missing assets keep their own status codes.
    @GetMapping({"/","/home","/practice","/foundations","/foundations/**","/account/forgot","/account/reset","/account/verify","/login","/register","/courses","/courses/**","/lessons","/lessons/**","/vocabulary","/vocabulary/**",
        "/learning","/profile","/progress","/progress/**","/flashcards","/flashcards/**",
        "/skills","/skills/**","/quizzes","/quizzes/**","/ai","/ai/**","/admin","/admin/**"})
    public String app() { return "forward:/index.html"; }
}

