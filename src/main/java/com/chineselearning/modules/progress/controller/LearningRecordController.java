package com.chineselearning.modules.progress.controller;

import com.chineselearning.modules.progress.service.LearningRecordService;
import com.chineselearning.modules.progress.repository.LearningRecordRepository.LearningRecord;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api/v1/learning-records")
public class LearningRecordController {
    public record CreateRecord(@NotNull @Positive Long lessonId, @NotNull @Size(max=2000,message="Ghi chú tối đa 2000 ký tự.") String note) {}
    public record UpdateRecord(@NotNull @Size(max=2000,message="Ghi chú tối đa 2000 ký tự.") String note) {}
    public record CompleteLesson(@NotNull @Positive Long lessonId) {}
    private final LearningRecordService service;
    public LearningRecordController(LearningRecordService service) { this.service=service; }
    @GetMapping public List<LearningRecord> list(Principal p) { return service.list(p.getName()); }
    @GetMapping("/{id}") public LearningRecord get(Principal p, @PathVariable UUID id) { return service.get(p.getName(),id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public LearningRecord create(Principal p, @Valid @RequestBody CreateRecord body) { return service.create(p.getName(),body.lessonId(),body.note()); }
    @PostMapping("/complete")
    public LearningRecord complete(Principal p, @Valid @RequestBody CompleteLesson body) { return service.complete(p.getName(),body.lessonId()); }
    @PatchMapping("/{id}") public LearningRecord update(Principal p, @PathVariable UUID id, @Valid @RequestBody UpdateRecord body) {
        return service.update(p.getName(),id,body.note());
    }
}
