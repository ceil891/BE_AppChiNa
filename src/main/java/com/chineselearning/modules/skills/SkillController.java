package com.chineselearning.modules.skills;

import com.chineselearning.modules.skills.SkillService.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {
    private final SkillService service;
    public SkillController(SkillService service) { this.service=service; }
    @GetMapping("/exercises") public List<Card> exercises() { return service.exercises(); }
    @GetMapping("/stats") public List<Stat> stats(Principal p) { return service.stats(p.getName()); }
    @GetMapping("/attempts") public Page history(Principal p,@RequestParam(defaultValue="") String skill,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.history(p.getName(),skill,page,size); }
    @PostMapping("/attempts") public Attempt start(Principal p,@Valid @RequestBody Start input) { return service.start(p.getName(),input); }
    @GetMapping("/attempts/{id}") public Attempt get(Principal p,@PathVariable UUID id) { return service.get(p.getName(),id); }
    @PutMapping("/attempts/{id}/draft") public Attempt draft(Principal p,@PathVariable UUID id,@Valid @RequestBody Draft input) { return service.saveDraft(p.getName(),id,input); }
    @PostMapping("/attempts/{id}/submit") public Attempt submit(Principal p,@PathVariable UUID id,@Valid @RequestBody Submit input) { return service.submit(p.getName(),id,input); }
}
