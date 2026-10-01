package com.chineselearning.modules.course;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import com.chineselearning.modules.course.CourseService.*;

@RestController
@RequestMapping("/api/v1")
public class CourseController {
    private final CourseService service;
    public CourseController(CourseService service) { this.service=service; }
    public record Publication(@NotNull Boolean published,@NotNull @Min(0) Long version) {}
    @GetMapping("/courses") public List<Course> publicList() { return service.list(false); }
    @GetMapping("/courses/{id}") public Detail publicGet(@PathVariable long id) { return service.get(id,false); }
    @GetMapping("/admin/courses") public List<Course> list() { return service.list(true); }
    @GetMapping("/admin/courses/{id}") public Detail get(@PathVariable long id) { return service.get(id,true); }
    @PostMapping("/admin/courses") @ResponseStatus(HttpStatus.CREATED) public Detail create(@Valid @RequestBody Input input) { return service.create(input); }
    @PutMapping("/admin/courses/{id}") public Detail update(@PathVariable long id,@Valid @RequestBody Input input) { return service.update(id,input); }
    @PatchMapping("/admin/courses/{id}/publication") public Detail publish(@PathVariable long id,@Valid @RequestBody Publication input) { return service.publish(id,input.version(),input.published()); }
    @DeleteMapping("/admin/courses/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable long id,@RequestParam long version) { service.delete(id,version); }
}
