package com.chineselearning.modules.lesson.controller;

import com.chineselearning.modules.lesson.dto.*;
import com.chineselearning.modules.lesson.service.LessonService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/lessons")
public class LessonController {
    private final LessonService service;
    public LessonController(LessonService service) { this.service = service; }
    @GetMapping public List<LessonSummary> list() { return service.list(); }
    @GetMapping("/{id}") public LessonDetail detail(@PathVariable long id) { return service.detail(id); }
}
