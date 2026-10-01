package com.chineselearning.modules.vocabulary.controller;

import com.chineselearning.modules.vocabulary.service.VocabularyService;
import com.chineselearning.modules.vocabulary.service.VocabularyService.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/vocabulary")
public class VocabularyController {
    private final VocabularyService service;
    public VocabularyController(VocabularyService service) { this.service = service; }
    @GetMapping
    public SearchResult search(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) Long lessonId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.search(q, lessonId, page, size);
    }
    @GetMapping("/{id}") public WordDetail detail(@PathVariable long id) { return service.detail(id); }
}
