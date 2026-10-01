package com.chineselearning.modules.progress.controller;

import com.chineselearning.modules.progress.dto.ProgressDtos.*;
import com.chineselearning.modules.progress.service.ProgressService;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController
@RequestMapping("/api/v1/progress")
public class ProgressController {
    private final ProgressService service;
    public ProgressController(ProgressService service) { this.service=service; }
    @GetMapping public Dashboard dashboard(Principal p) { return service.dashboard(p.getName()); }
    @GetMapping("/events") public ActivityPage history(Principal p,@RequestParam(defaultValue="") String kind,
        @RequestParam(defaultValue="") String date,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return service.history(p.getName(),kind,date,page,size);
    }
}
