package com.chineselearning.modules.foundation;

import com.chineselearning.modules.foundation.FoundationModels.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class FoundationController {
    private final FoundationService service;
    public FoundationController(FoundationService service) { this.service=service; }
    @GetMapping("/foundations") public List<Card> list() { return service.list(); }
    @GetMapping("/foundations/{slug}") public Lesson get(@PathVariable String slug) { return service.get(slug); }
    @PostMapping("/foundations/{slug}/check") public Result check(Principal p,@PathVariable String slug,@Valid @RequestBody Submit body) { return service.submit(p==null?null:p.getName(),slug,body); }
    @GetMapping("/foundation-progress") public List<Progress> progress(Principal p) { return service.progress(p.getName()); }
    @PutMapping("/foundation-progress/{slug}") public Progress bookmark(Principal p,@PathVariable String slug,@Valid @RequestBody Bookmark body) { return service.bookmark(p.getName(),slug,body); }
    @GetMapping("/admin/foundations") public List<Detail> adminList(Principal p) { return service.adminList(p.getName()); }
    @GetMapping("/admin/foundations/{slug}") public Detail adminGet(Principal p,@PathVariable String slug) { return service.adminGet(p.getName(),slug); }
    @PostMapping("/admin/foundations") @ResponseStatus(HttpStatus.CREATED) public Detail create(Principal p,@Valid @RequestBody Input body) { return service.create(p.getName(),body); }
    @PutMapping("/admin/foundations/{slug}") public Detail update(Principal p,@PathVariable String slug,@Valid @RequestBody Input body) { return service.update(p.getName(),slug,body); }
    @PatchMapping("/admin/foundations/{slug}/publication") public Detail publish(Principal p,@PathVariable String slug,@Valid @RequestBody Publication body) { return service.publish(p.getName(),slug,body); }
}
