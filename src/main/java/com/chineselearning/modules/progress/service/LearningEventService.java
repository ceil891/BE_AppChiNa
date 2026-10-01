package com.chineselearning.modules.progress.service;

import com.chineselearning.modules.progress.dto.ProgressDtos.Kind;
import com.chineselearning.modules.progress.repository.LearningEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Internal event writer: source activity and event commit or roll back together. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class LearningEventService {
    private final LearningEventRepository events;
    public LearningEventService(LearningEventRepository events) { this.events=events; }
    public void lesson(UUID owner, UUID record, long lesson, String title, OffsetDateTime at) {
        events.record(owner,Kind.LESSON_COMPLETED,record,at,title,lesson,null,null);
    }
    public void flashcard(UUID owner, UUID request, String hanzi, String rating, OffsetDateTime at) {
        events.record(owner,Kind.FLASHCARD_REVIEWED,request,at,hanzi,null,null,rating);
    }
    public void quiz(UUID owner, UUID attempt, String title, int score, OffsetDateTime at) {
        events.record(owner,Kind.QUIZ_SUBMITTED,attempt,at,title,null,score,null);
    }
    public void foundation(UUID owner, UUID attempt, String title, int score, OffsetDateTime at) {
        events.record(owner,Kind.FOUNDATION_SUBMITTED,attempt,at,title,null,score,null);
    }
    public void skill(UUID owner, UUID attempt, String title, Integer score, OffsetDateTime at) {
        events.record(owner,Kind.SKILL_PRACTICED,attempt,at,title,null,score,null);
    }
}
