package com.chineselearning.modules.course;

import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

@Service
public class CourseService {
    public record Input(@NotBlank @Size(max=120) String title, @NotNull @Size(max=2000) String description,
        @NotNull @Size(max=100) List<@NotNull @Positive Long> lessonIds, @NotNull @Min(0) Long version) {}
    public record Course(long id,String title,String description,boolean published,long version) {}
    public record Lesson(long id,String title,boolean published) {}
    public record Detail(Course course,List<Lesson> lessons) {}
    private final JdbcClient jdbc;
    public CourseService(JdbcClient jdbc) { this.jdbc=jdbc; }
    public List<Course> list(boolean admin) {
        return jdbc.sql("SELECT * FROM courses "+(admin?"":"WHERE published ")+"ORDER BY id DESC").query(Course.class).list();
    }
    public Detail get(long id,boolean admin) {
        var course=jdbc.sql("SELECT * FROM courses WHERE id=:id"+(admin?"":" AND published"))
            .param("id",id).query(Course.class).optional().orElseThrow(()->problem(HttpStatus.NOT_FOUND,"Không tìm thấy khóa học."));
        var lessons=jdbc.sql("SELECT l.id,l.title,l.published FROM course_lessons c JOIN lessons l ON l.id=c.lesson_id WHERE c.course_id=:id "+(admin?"":"AND l.published ")+"ORDER BY c.position")
            .param("id",id).query(Lesson.class).list();
        return new Detail(course,lessons);
    }
    private void validate(Input input) {
        if(new HashSet<>(input.lessonIds()).size()!=input.lessonIds().size()) throw problem(HttpStatus.BAD_REQUEST,"Bài học bị trùng.");
        for(long id:input.lessonIds()) if(!jdbc.sql("SELECT EXISTS(SELECT 1 FROM lessons WHERE id=:id FOR SHARE)").param("id",id).query(Boolean.class).single())
            throw problem(HttpStatus.BAD_REQUEST,"Một bài học không còn tồn tại. Hãy tải lại danh sách.");
    }
    private Course lock(long id,long version) {
        var course=jdbc.sql("SELECT * FROM courses WHERE id=:id FOR UPDATE").param("id",id).query(Course.class).optional()
            .orElseThrow(()->problem(HttpStatus.NOT_FOUND,"Không tìm thấy khóa học."));
        if(course.version()!=version) throw problem(HttpStatus.CONFLICT,"Khóa học đã được cập nhật ở nơi khác. Hãy tải lại.");
        return course;
    }
    private void attach(long id,List<Long> lessons) {
        jdbc.sql("DELETE FROM course_lessons WHERE course_id=:id").param("id",id).update();
        for(int i=0;i<lessons.size();i++) jdbc.sql("INSERT INTO course_lessons VALUES (:id,:lesson,:position)")
            .param("id",id).param("lesson",lessons.get(i)).param("position",i+1).update();
    }
    @Transactional public Detail create(Input input) {
        validate(input);
        long id=jdbc.sql("INSERT INTO courses(title,description) VALUES (:title,:description) RETURNING id")
            .param("title",input.title().strip()).param("description",input.description().strip()).query(Long.class).single();
        attach(id,input.lessonIds());return get(id,true);
    }
    @Transactional public Detail update(long id,Input input) {
        var course=lock(id,input.version());
        if(course.published()) throw problem(HttpStatus.CONFLICT,"Ẩn khóa học trước khi chỉnh sửa nội dung.");
        validate(input);
        jdbc.sql("UPDATE courses SET title=:title,description=:description,version=version+1 WHERE id=:id")
            .param("title",input.title().strip()).param("description",input.description().strip()).param("id",id).update();
        attach(id,input.lessonIds());return get(id,true);
    }
    @Transactional public Detail publish(long id,long version,boolean published) {
        lock(id,version);
        var lessons=get(id,true).lessons();
        if(published && (lessons.isEmpty() || lessons.stream().anyMatch(l->!l.published())))
            throw problem(HttpStatus.CONFLICT,"Khóa học cần ít nhất một bài và tất cả bài phải đang hiển thị.");
        jdbc.sql("UPDATE courses SET published=:published,version=version+1 WHERE id=:id")
            .param("id",id).param("published",published).update();return get(id,true);
    }
    @Transactional public void delete(long id,long version) {
        if(lock(id,version).published()) throw problem(HttpStatus.CONFLICT,"Ẩn khóa học trước khi xóa.");
        jdbc.sql("DELETE FROM courses WHERE id=:id").param("id",id).update();
    }
    private static ResponseStatusException problem(HttpStatus status,String message) { return new ResponseStatusException(status,message); }
}
