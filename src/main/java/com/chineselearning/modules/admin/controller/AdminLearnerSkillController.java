package com.chineselearning.modules.admin.controller;

import com.chineselearning.modules.skills.SkillService;
import com.chineselearning.modules.skills.SkillService.*;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/users/{learner}/skills")
public class AdminLearnerSkillController {
    private final SkillService service;
    public AdminLearnerSkillController(SkillService service) { this.service=service; }
    @GetMapping public List<Stat> stats(Principal p,@PathVariable UUID learner) { return service.learnerStats(p.getName(),learner); }
    @GetMapping("/attempts") public Page history(Principal p,@PathVariable UUID learner,
        @RequestParam(defaultValue="") String skill,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return service.learnerHistory(p.getName(),learner,skill,page,size);
    }
    @GetMapping("/attempts/{attempt}") public Attempt attempt(Principal p,@PathVariable UUID learner,@PathVariable UUID attempt) {
        return service.learnerAttempt(p.getName(),learner,attempt);
    }
}
