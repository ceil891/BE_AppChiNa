package com.chineselearning.modules.progress.service;

import com.chineselearning.modules.progress.dto.ProgressDtos.*;
import com.chineselearning.modules.progress.repository.LearningEventRepository;
import com.chineselearning.modules.user.service.UserService;
import com.chineselearning.modules.lesson.service.LessonService;
import com.chineselearning.modules.flashcard.service.FlashcardService;
import com.chineselearning.modules.quiz.service.QuizService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProgressService {
    private final LearningEventRepository events;
    private final UserService users;
    private final LessonService lessons;
    private final FlashcardService flashcards;
    private final QuizService quizzes;
    private final Clock clock;
    public ProgressService(LearningEventRepository events,UserService users,LessonService lessons,FlashcardService flashcards,QuizService quizzes,Clock clock) {
        this.events=events;this.users=users;this.lessons=lessons;this.flashcards=flashcards;this.quizzes=quizzes;this.clock=clock;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Dashboard dashboard(String email) {
        var user=users.current(email);var owner=user.id();var now=OffsetDateTime.now(clock);
        var zone=ZoneId.of(user.timeZone());var today=now.atZoneSameInstant(zone).toLocalDate();
        var dates=events.activeDates(owner,now);var from=today.minusDays(27);
        var counts=events.days(owner,from,today,now).stream().collect(Collectors.toMap(Day::date,Function.identity()));
        var calendar=from.datesUntil(today.plusDays(1)).map(day -> counts.getOrDefault(day,new Day(day,0,0,0,0))).toList();
        var allLessons=lessons.list();var completed=events.completedLessons(owner,now);
        var cards=flashcards.statsForProgress(owner,now);
        var draft=quizzes.list(email,"IN_PROGRESS",0,1).items();
        NextAction next;
        if (cards.due()>0) next=new NextAction("FLASHCARDS","Ôn thẻ đến hạn",cards.due()+" thẻ đang chờ bạn ôn lại.","/flashcards/review");
        else if (!draft.isEmpty()) next=new NextAction("QUIZ","Làm tiếp Quiz",draft.get(0).title(),"/quizzes/attempts/"+draft.get(0).id());
        else {
            var lesson=allLessons.stream().filter(l -> l.wordCount()>0 && !completed.contains(l.id())).findFirst();
            next=lesson.map(l -> new NextAction("LESSON","Học bài tiếp theo",l.title(),"/lessons/"+l.id()))
                .orElseGet(() -> new NextAction("QUIZ","Luyện một lượt Quiz","Ôn lại các từ đã học qua câu hỏi ngắn.","/quizzes"));
        }
        return new Dashboard(user.timeZone(),today,now,today.plusDays(1).atStartOfDay(zone).toOffsetDateTime(),
            StreakCalculator.calculate(dates,today),events.totals(owner,now),allLessons.size(),cards,calendar.get(calendar.size()-1),calendar,
            events.list(owner,"","",0,5,now),next);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ActivityPage history(String email,String kind,String date,int page,int size) {
        if(page<0 || size<1 || size>50 || !List.of("","LESSON_COMPLETED","FLASHCARD_REVIEWED","QUIZ_SUBMITTED","SKILL_PRACTICED","FOUNDATION_SUBMITTED").contains(kind))
            throw bad("Bộ lọc không hợp lệ; trang từ 0, số mục từ 1–50.");
        if(!date.isEmpty()) try {
            if(!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || LocalDate.parse(date).getYear()<1)
                throw bad("Ngày phải có định dạng YYYY-MM-DD hợp lệ.");
        } catch(DateTimeParseException ex) { throw bad("Ngày phải có định dạng YYYY-MM-DD hợp lệ."); }
        var user=users.current(email);var now=OffsetDateTime.now(clock);
        return new ActivityPage(events.list(user.id(),kind,date,page,size,now),events.count(user.id(),kind,date,now),page,size,user.timeZone());
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
