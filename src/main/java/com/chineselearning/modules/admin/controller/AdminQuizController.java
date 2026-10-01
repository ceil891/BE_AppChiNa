package com.chineselearning.modules.admin.controller;

import com.chineselearning.modules.admin.service.AdminQuizService;
import com.chineselearning.modules.admin.service.AdminQuizService.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/quizzes")
public class AdminQuizController {
    private final AdminQuizService service;
    public AdminQuizController(AdminQuizService service) { this.service=service; }
    @GetMapping public List<Summary> list() { return service.list(); }
    @GetMapping("/{id}") public Detail get(@PathVariable long id) { return service.get(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Detail create(@Valid @RequestBody Input input) { return service.create(input); }
    @PutMapping("/{id}") public Detail update(@PathVariable long id,@Valid @RequestBody Input input) { return service.update(id,input); }
    @PatchMapping("/{id}/publication") public Detail publish(@PathVariable long id,@Valid @RequestBody Publication input) { return service.publish(id,input); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable long id,@RequestParam long version) { service.delete(id,version); }
}
