package com.chineselearning.modules.quiz.controller;

import com.chineselearning.modules.quiz.dto.QuizDtos.*;
import com.chineselearning.modules.quiz.service.QuizService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/quizzes")
public class QuizController {
    private final QuizService service;
    public QuizController(QuizService service) { this.service=service; }
    @GetMapping("/sets") public List<SetSummary> sets() { return service.sets(); }
    @GetMapping("/attempts") public AttemptPage list(Principal p, @RequestParam(defaultValue="") String status,
        @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) { return service.list(p.getName(),status,page,size); }
    @PostMapping("/attempts") public Attempt start(Principal p,@Valid @RequestBody Start input) { return service.start(p.getName(),input); }
    @GetMapping("/attempts/{id}") public Attempt get(Principal p,@PathVariable UUID id) { return service.get(p.getName(),id); }
    @PutMapping("/attempts/{id}/answers") public Attempt answer(Principal p,@PathVariable UUID id,@Valid @RequestBody Answer input) { return service.answer(p.getName(),id,input); }
    @PostMapping("/attempts/{id}/submit") public Attempt submit(Principal p,@PathVariable UUID id,@Valid @RequestBody Submit input) { return service.submit(p.getName(),id,input); }
}
