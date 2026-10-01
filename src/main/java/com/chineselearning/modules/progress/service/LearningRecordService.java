package com.chineselearning.modules.progress.service;

import com.chineselearning.modules.progress.repository.LearningRecordRepository;
import com.chineselearning.modules.progress.repository.LearningRecordRepository.LearningRecord;
import com.chineselearning.modules.user.service.UserService;
import com.chineselearning.modules.lesson.service.LessonService;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningRecordService {
    private final LearningRecordRepository records;
    private final UserService users;
    private final LessonService lessons;
    private final LearningEventService events;
    private final Clock clock;
    public LearningRecordService(LearningRecordRepository records, UserService users, LessonService lessons, LearningEventService events, Clock clock) {
        this.records=records; this.users=users; this.lessons=lessons; this.events=events; this.clock=clock;
    }
    public List<LearningRecord> list(String email) { return records.list(users.current(email).id()); }
    public LearningRecord get(String email, UUID id) {
        return records.get(id,users.current(email).id()).orElseThrow(this::notFound);
    }
    public LearningRecord create(String email, long lesson, String note) {
        lessons.detail(lesson);
        try { return records.create(users.current(email).id(),lesson,note); }
        catch (DuplicateKeyException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Bạn đã lưu bài học này."); }
    }
    public LearningRecord update(String email, UUID id, String note) {
        return records.update(id,users.current(email).id(),note).orElseThrow(this::notFound);
    }
    @Transactional
    public LearningRecord complete(String email, long lesson) {
        var detail=lessons.detail(lesson);
        if (detail.words().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Bài chưa có từ vựng để học.");
        }
        var owner=users.current(email).id();
        var result=records.complete(owner,lesson,OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
        events.lesson(owner,result.id(),lesson,detail.lesson().title(),result.completedAt());
        return result;
    }
    private ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy dữ liệu học."); }
}
