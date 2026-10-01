package com.chineselearning.modules.lesson.service;

import com.chineselearning.modules.lesson.dto.*;
import com.chineselearning.modules.lesson.repository.LessonRepository;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service
public class LessonService {
    private final LessonRepository repository;
    public LessonService(LessonRepository repository) { this.repository = repository; }
    public List<LessonSummary> list() { return repository.findAll().stream().filter(LessonSummary::published).toList(); }
    public List<LessonSummary> adminList() { return repository.findAll(); }
    public LessonDetail adminDetail(long id) {
        var lesson=repository.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy bài học"));
        return new LessonDetail(lesson,repository.findWords(id));
    }
    public LessonDetail publication(long id,boolean published) {
        if(!repository.publication(id,published))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy bài học");
        return adminDetail(id);
    }
    public LessonDetail detail(long id) {
        var lesson = repository.findById(id).filter(LessonSummary::published).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài học"));
        return new LessonDetail(lesson, repository.findWords(id));
    }
}
