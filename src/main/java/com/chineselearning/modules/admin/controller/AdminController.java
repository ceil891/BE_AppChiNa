package com.chineselearning.modules.admin.controller;
import com.chineselearning.modules.admin.dto.AdminRequests.*;
import com.chineselearning.modules.admin.service.AdminService;
import com.chineselearning.modules.lesson.dto.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminService service;
    public AdminController(AdminService service) { this.service=service; }
    @GetMapping("/vocabulary") public List<Word> words() { return service.words(); }
    @GetMapping("/vocabulary/{id}") public Word word(@PathVariable long id) { return service.word(id); }
    @PostMapping("/vocabulary") @ResponseStatus(HttpStatus.CREATED)
    public Word createWord(@Valid @RequestBody VocabularyInput body) { return service.createWord(body); }
    @PutMapping("/vocabulary/{id}") public Word updateWord(@PathVariable long id,@Valid @RequestBody VocabularyInput body) { return service.updateWord(id,body); }
    @DeleteMapping("/vocabulary/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWord(@PathVariable long id) { service.deleteWord(id); }
    public record Publication(@jakarta.validation.constraints.NotNull Boolean published) {}
    @PatchMapping("/lessons/{id}/publication") public LessonDetail publication(@PathVariable long id,@Valid @RequestBody Publication input) { return service.publication(id,input.published()); }
    @GetMapping("/lessons") public List<LessonSummary> lessons() { return service.lessons(); }
    @GetMapping("/lessons/{id}") public LessonDetail lesson(@PathVariable long id) { return service.lesson(id); }
    @PostMapping("/lessons") @ResponseStatus(HttpStatus.CREATED)
    public LessonDetail createLesson(@Valid @RequestBody LessonInput body) { return service.createLesson(body); }
    @PutMapping("/lessons/{id}") public LessonDetail updateLesson(@PathVariable long id,@Valid @RequestBody LessonInput body) { return service.updateLesson(id,body); }
    @DeleteMapping("/lessons/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLesson(@PathVariable long id) { service.deleteLesson(id); }
}
