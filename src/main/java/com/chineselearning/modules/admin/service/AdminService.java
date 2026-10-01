package com.chineselearning.modules.admin.service;
import com.chineselearning.modules.admin.dto.AdminRequests.*;
import com.chineselearning.modules.admin.repository.AdminRepository;
import com.chineselearning.modules.lesson.dto.*;
import com.chineselearning.modules.lesson.service.LessonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class AdminService {
    private final AdminRepository repository;
    private final LessonService lessons;
    public AdminService(AdminRepository repository,LessonService lessons) { this.repository=repository; this.lessons=lessons; }
    public List<Word> words() { return repository.words(); }
    public Word word(long id) { return repository.word(id).orElseThrow(this::notFound); }
    public Word createWord(VocabularyInput input) { return repository.createWord(input); }
    public Word updateWord(long id,VocabularyInput input) { return repository.updateWord(id,input).orElseThrow(this::notFound); }
    public void deleteWord(long id) {
        try { if(repository.deleteWord(id)==0) throw notFound(); }
        catch(DataIntegrityViolationException ex) { throw conflict("Từ đang thuộc bài học hoặc đã được người học lưu để ôn tập. Không thể xóa khi còn dữ liệu liên kết."); }
    }
    public List<LessonSummary> lessons() { return lessons.adminList(); }
    public LessonDetail lesson(long id) { return lessons.adminDetail(id); }
    @Transactional public LessonDetail publication(long id,boolean published) { return lessons.publication(id,published); }
    @Transactional public LessonDetail createLesson(LessonInput input) {
        validateWords(input.wordIds());
        try {
            long id=repository.createLesson(input);
            repository.attachWords(id,input.wordIds());
            return lessons.adminDetail(id);
        } catch(DataIntegrityViolationException ex) { throw conflict("Thứ tự bài đã được sử dụng hoặc danh sách từ đã thay đổi. Hãy tải lại và kiểm tra."); }
    }
    @Transactional public LessonDetail updateLesson(long id,LessonInput input) {
        if(!repository.lockLesson(id)) throw notFound();
        validateWords(input.wordIds());
        try {
            repository.updateLesson(id,input);
            repository.clearWords(id);
            repository.attachWords(id,input.wordIds());
            return lessons.adminDetail(id);
        } catch(DataIntegrityViolationException ex) { throw conflict("Thứ tự bài đã được sử dụng hoặc danh sách từ đã thay đổi. Hãy tải lại và kiểm tra."); }
    }
    @Transactional public void deleteLesson(long id) {
        if(!repository.lockLesson(id)) throw notFound();
        try {
            repository.clearWords(id);
            repository.deleteLesson(id);
        } catch(DataIntegrityViolationException ex) { throw conflict("Bài học đã được người học lưu. Không thể xóa để tránh mất dữ liệu học."); }
    }
    private void validateWords(List<Long> ids) {
        if(new HashSet<>(ids).size()!=ids.size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Danh sách có từ bị trùng.");
        var existing=new HashSet<>(repository.words().stream().map(Word::id).toList());
        if(!existing.containsAll(ids)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Có từ vựng không tồn tại. Hãy tải lại danh sách.");
    }
    private ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy nội dung."); }
    private ResponseStatusException conflict(String detail) { return new ResponseStatusException(HttpStatus.CONFLICT,detail); }
}
