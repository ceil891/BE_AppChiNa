package com.chineselearning.modules.skills;

import com.chineselearning.modules.skills.SkillCatalog.Skill;
import com.chineselearning.modules.skills.AdminSkillService.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/skills/{skill}")
public class AdminSkillController {
    private final AdminSkillService service;
    public AdminSkillController(AdminSkillService service) { this.service=service; }
    @GetMapping public List<Detail> list(Principal p,@PathVariable Skill skill) { return service.list(p.getName(),skill); }
    @GetMapping("/{id}") public Detail get(Principal p,@PathVariable Skill skill,@PathVariable String id) { return service.get(p.getName(),skill,id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Detail create(Principal p,@PathVariable Skill skill,@Valid @RequestBody Input input) { return service.create(p.getName(),skill,input); }
    @PutMapping("/{id}") public Detail update(Principal p,@PathVariable Skill skill,@PathVariable String id,@Valid @RequestBody Input input) { return service.update(p.getName(),skill,id,input); }
    @PatchMapping("/{id}/publication") public Detail publish(Principal p,@PathVariable Skill skill,@PathVariable String id,@Valid @RequestBody Publication input) { return service.publish(p.getName(),skill,id,input); }
    @GetMapping("/{id}/results") public Results results(Principal p,@PathVariable Skill skill,@PathVariable String id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.results(p.getName(),skill,id,page,size); }
}
