package com.chineselearning.modules.vocabulary.service;

import com.chineselearning.modules.lesson.dto.*;
import com.chineselearning.modules.lesson.service.LessonService;
import com.chineselearning.modules.vocabulary.repository.VocabularyRepository;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service
public class VocabularyService {
    public record SearchResult(List<Word> items, long total, int page, int size) {}
    public record WordDetail(Word word, List<LessonSummary> lessons) {}
    private final VocabularyRepository repository;
    private final LessonService lessons;
    public VocabularyService(VocabularyRepository repository, LessonService lessons) {
        this.repository = repository; this.lessons = lessons;
    }
    public SearchResult search(String query, Long lessonId, int page, int size) {
        String normalized = query.strip();
        if (normalized.length() > 100 || page < 0 || size < 1 || size > 50 || (lessonId != null && lessonId < 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Từ khóa tối đa 100 ký tự, trang từ 0, số từ mỗi trang từ 1–50 và ID bài phải dương.");
        }
        if (lessonId != null) lessons.detail(lessonId);
        long lesson = lessonId == null ? 0 : lessonId;
        return new SearchResult(repository.search(normalized, lesson, page, size), repository.count(normalized, lesson), page, size);
    }
    public WordDetail detail(long id) {
        var word = repository.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy từ vựng."));
        var ids = repository.lessonIds(id);
        return new WordDetail(word, lessons.list().stream().filter(l -> ids.contains(l.id())).toList());
    }
}
