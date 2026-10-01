package com.chineselearning.modules.flashcard.service;

import com.chineselearning.modules.flashcard.dto.FlashcardDtos.*;
import com.chineselearning.modules.flashcard.repository.FlashcardRepository;
import com.chineselearning.modules.user.service.UserService;
import com.chineselearning.modules.lesson.service.LessonService;
import com.chineselearning.modules.vocabulary.service.VocabularyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import com.chineselearning.modules.progress.service.LearningEventService;

@Service
public class FlashcardService {
    private final FlashcardRepository cards;
    private final UserService users;
    private final LessonService lessons;
    private final VocabularyService vocabulary;
    private final Clock clock;
    private final LearningEventService events;
    public FlashcardService(FlashcardRepository cards,UserService users,LessonService lessons,VocabularyService vocabulary,Clock clock,LearningEventService events){
        this.cards=cards;this.users=users;this.lessons=lessons;this.vocabulary=vocabulary;this.clock=clock;this.events=events;
    }
    public Stats statsForProgress(UUID owner,OffsetDateTime at){return cards.stats(owner,"",0,at);}
    private OffsetDateTime now(){return OffsetDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    private void page(int page,int size){if(page<0 || size<1 || size>50)throw problem(HttpStatus.BAD_REQUEST,"Trang từ 0, số mục mỗi trang từ 1–50.");}
    public CardPage list(String email,String q,Long lesson,boolean dueOnly,int page,int size){
        page(page,size);
        if(q.length()>100 || (lesson!=null && lesson<=0))throw problem(HttpStatus.BAD_REQUEST,"Từ khóa tối đa 100 ký tự và ID bài phải dương.");
        if(lesson!=null)lessons.detail(lesson);
        var owner=users.current(email).id();var now=now();long lessonId=lesson==null?0:lesson;
        var stats=cards.stats(owner,q.strip(),lessonId,now);
        return new CardPage(cards.list(owner,q.strip(),lessonId,dueOnly,page,size,now),dueOnly?stats.due():stats.saved(),page,size,stats,now);
    }
    public Card get(String email,UUID id){return cards.get(users.current(email).id(),id).orElseThrow(this::missing);}
    public CardLookup byWord(String email,long id){vocabulary.detail(id);return new CardLookup(cards.byWord(users.current(email).id(),id).orElse(null));}
    @Transactional public Card save(String email,long word){
        vocabulary.detail(word);var owner=users.current(email).id();
        try{return cards.get(owner,cards.save(owner,word,now())).orElseThrow(this::missing);}
        catch(DataIntegrityViolationException ex){throw problem(HttpStatus.CONFLICT,"Từ vựng vừa thay đổi. Vui lòng tải lại.");}
    }
    @Transactional public SavedLesson saveLesson(String email,long id){
        var detail=lessons.detail(id);var owner=users.current(email).id();var now=now();
        if(detail.words().isEmpty())throw problem(HttpStatus.CONFLICT,"Bài chưa có từ để lưu.");
        try{for(var word:detail.words().stream().sorted(Comparator.comparingLong(com.chineselearning.modules.lesson.dto.Word::id)).toList())cards.save(owner,word.id(),now);}
        catch(DataIntegrityViolationException ex){throw problem(HttpStatus.CONFLICT,"Nội dung bài vừa thay đổi. Vui lòng tải lại.");}
        return new SavedLesson(detail.words().size());
    }
    @Transactional public void archive(String email,UUID id){
        var owner=users.current(email).id();cards.lock(owner,id).orElseThrow(this::missing);cards.archive(owner,id);
    }
    @Transactional public Review review(String email,UUID id,SubmitReview input){
        var owner=users.current(email).id();var card=cards.lock(owner,id).orElseThrow(this::missing);
        var existing=cards.review(owner,input.requestId());
        if(existing.isPresent()){
            var old=existing.get();
            if(!old.cardId().equals(id) || old.rating()!=input.rating() || old.requestVersion()!=input.version())
                throw problem(HttpStatus.CONFLICT,"Mã lượt ôn đã dùng cho câu trả lời khác.");
            return old;
        }
        if(card.archived())throw missing();
        if(card.version()!=input.version())throw problem(HttpStatus.CONFLICT,"Thẻ đã thay đổi hoặc được ôn ở nơi khác. Hãy tải lại hàng đợi.");
        var now=now();
        if(card.nextReviewAt().isAfter(now))throw problem(HttpStatus.CONFLICT,"Thẻ chưa đến hạn ôn.");
        var word=vocabulary.detail(card.vocabularyId()).word();
        var next=ReviewSchedule.next(card.successStreak(),input.rating(),now);
        var review=new Review(input.requestId(),id,word.id(),word.hanzi(),word.pinyin(),word.meaningVi(),input.rating(),card.version(),card.version()+1,
            now,next.dueAt(),next.successStreak(),card.reviewCount()+1);
        try{cards.record(owner,review);}
        catch(DataIntegrityViolationException ex){throw problem(HttpStatus.CONFLICT,"Lượt ôn đã thay đổi. Hãy tải lại hàng đợi.");}
        events.flashcard(owner,review.requestId(),review.hanzi(),review.rating().name(),review.reviewedAt());
        return review;
    }
    public HistoryPage history(String email,int page,int size){page(page,size);var owner=users.current(email).id();return new HistoryPage(cards.history(owner,page,size),cards.historyCount(owner),page,size);}
    private ResponseStatusException missing(){return problem(HttpStatus.NOT_FOUND,"Không tìm thấy thẻ đã lưu.");}
    private ResponseStatusException problem(HttpStatus status,String detail){return new ResponseStatusException(status,detail);}
}
