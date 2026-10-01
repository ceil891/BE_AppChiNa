package com.chineselearning;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ProgressApiTests.ClockConfig.class)
class ProgressApiTests {
    static final Instant BEFORE_MIDNIGHT=Instant.parse("2026-09-14T16:59:59Z");
    static class StudyClock extends Clock {
        private final AtomicReference<Instant> value;private final ZoneId zone;
        StudyClock(){this(new AtomicReference<>(BEFORE_MIDNIGHT),ZoneOffset.UTC);}
        StudyClock(AtomicReference<Instant> value,ZoneId zone){this.value=value;this.zone=zone;}
        void set(String instant){value.set(Instant.parse(instant));}
        public ZoneId getZone(){return zone;}
        public Clock withZone(ZoneId zone){return new StudyClock(value,zone);}
        public Instant instant(){return value.get();}
    }
    @TestConfiguration static class ClockConfig { @Bean @Primary StudyClock studyClock(){return new StudyClock();} }
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired StudyClock clock;
    AdminUserApiTests api;QuizApiTests quiz;
    @BeforeEach void setup(){
        clock.set(BEFORE_MIDNIGHT.toString());api=new AdminUserApiTests();api.port=port;api.jdbc=jdbc;api.mapper=mapper;
        quiz=new QuizApiTests();quiz.api=api;quiz.jdbc=jdbc;quiz.mapper=mapper;
    }
    @AfterEach void cleanup(){api.cleanup();}
    AdminUserApiTests.Client learner()throws Exception{return quiz.learner();}
    JsonNode dashboard(AdminUserApiTests.Client c)throws Exception {var r=c.get("/progress");api.status(r,200);return api.json(r);}
    JsonNode complete(AdminUserApiTests.Client c,long lesson)throws Exception {
        var r=c.write("POST","/learning-records/complete",Map.of("lessonId",lesson));api.status(r,200);return api.json(r);
    }
    JsonNode save(AdminUserApiTests.Client c,long word)throws Exception {
        var r=c.write("POST","/flashcards",Map.of("vocabularyId",word));api.status(r,200);return api.json(r);
    }
    Map<String,Object> reviewInput(JsonNode card){return Map.of("requestId",UUID.randomUUID(),"rating","AGAIN","version",card.get("version").asLong());}
    String reviewPath(JsonNode card){return "/flashcards/"+card.get("id").asText()+"/reviews";}
    UUID owner(AdminUserApiTests.Client c)throws Exception{return UUID.fromString(api.json(c.get("/auth/me")).get("id").asText());}
    long events(UUID owner){return jdbc.sql("SELECT COUNT(*) FROM learning_events WHERE user_id=:id").param("id",owner).query(Long.class).single();}

    @Test void viewingSavingAndDraftAnswersDoNotCountAsStudyActivity()throws Exception {
        var c=learner();var empty=dashboard(c);
        assertThat(empty.get("streak").get("current").asInt()).isZero();assertThat(empty.get("totals").get("averageQuizScore").isNull()).isTrue();
        assertThat(empty.get("calendar").size()).isEqualTo(28);assertThat(empty.get("nextAction").get("path").asText()).isEqualTo("/lessons/1");
        api.status(c.get("/lessons/1"),200);api.status(c.get("/vocabulary/1"),200);api.status(c.get("/profile"),200);
        api.status(c.write("POST","/learning-records",Map.of("lessonId",1,"note","Private note never included in activity")),201);
        save(c,1);var draft=quiz.start(c,5);var q=draft.get("questions").get(0);quiz.answer(c,draft,q,quiz.correct(q));
        var after=dashboard(c);assertThat(events(owner(c))).isZero();assertThat(after.get("todayActivity").get("totalActivities").asInt()).isZero();
        assertThat(after.get("nextAction").get("kind").asText()).isEqualTo("FLASHCARDS");
        assertThat(c.get("/progress").body()).doesNotContain("Private note");
    }
    @Test void threeActivityKindsSameDayCountOnceAndRetriesPreserveTotals()throws Exception {
        var c=learner();var completion=complete(c,1);assertThat(complete(c,1)).isEqualTo(completion);
        var card=save(c,1);var input=reviewInput(card);api.status(c.write("POST",reviewPath(card),input),200);api.status(c.write("POST",reviewPath(card),input),200);
        var draft=quiz.fill(c,quiz.start(c,5),2);var result=quiz.submit(c,draft);assertThat(quiz.submit(c,draft)).isEqualTo(result);
        var d=dashboard(c);assertThat(d.get("streak").get("activeDays").asInt()).isEqualTo(1);assertThat(d.get("streak").get("current").asInt()).isEqualTo(1);
        assertThat(d.get("todayActivity").get("totalActivities").asInt()).isEqualTo(3);assertThat(d.get("totals").get("completedLessons").asInt()).isEqualTo(1);
        assertThat(d.get("totals").get("flashcardReviews").asInt()).isEqualTo(1);assertThat(d.get("totals").get("submittedQuizzes").asInt()).isEqualTo(1);
        assertThat(d.get("totals").get("averageQuizScore").asDouble()).isEqualTo(40);assertThat(events(owner(c))).isEqualTo(3);
        quiz.submit(c,quiz.fill(c,quiz.start(c,5),0));d=dashboard(c);
        assertThat(d.get("totals").get("averageQuizScore").asDouble()).isEqualTo(20);assertThat(d.get("streak").get("activeDays").asInt()).isEqualTo(1);
    }
    @Test void vietnamMidnightRolloverAndMissedDaysUseServerClock()throws Exception {
        var c=learner();var first=complete(c,1);var d=dashboard(c);
        assertThat(d.get("today").asText()).isEqualTo("2026-09-14");assertThat(OffsetDateTime.parse(d.get("nextDayAt").asText()).toInstant()).isEqualTo(Instant.parse("2026-09-14T17:00:00Z"));
        clock.set("2026-09-14T17:00:00Z");assertThat(complete(c,1)).isEqualTo(first);d=dashboard(c);
        assertThat(d.get("today").asText()).isEqualTo("2026-09-15");assertThat(d.get("streak").get("current").asInt()).isEqualTo(1);
        assertThat(d.get("streak").get("studiedToday").asBoolean()).isFalse();assertThat(d.get("todayActivity").get("totalActivities").asInt()).isZero();
        var card=save(c,1);api.status(c.write("POST",reviewPath(card),reviewInput(card)),200);d=dashboard(c);
        assertThat(d.get("streak").get("current").asInt()).isEqualTo(2);assertThat(d.get("streak").get("studiedToday").asBoolean()).isTrue();
        assertThat(d.get("calendar").get(27).get("date").asText()).isEqualTo("2026-09-15");assertThat(d.get("calendar").get(27).get("totalActivities").asInt()).isEqualTo(1);
        var account=api.json(c.get("/auth/me"));
        clock.set("2026-09-16T17:00:00Z");
        api.status(c.get("/auth/me"),401); // Two days later the prior session must have expired.
        c=api.login(account,AdminUserApiTests.PASSWORD);d=dashboard(c);
        assertThat(d.get("today").asText()).isEqualTo("2026-09-17");assertThat(d.get("streak").get("current").asInt()).isZero();assertThat(d.get("streak").get("longest").asInt()).isEqualTo(2);
        complete(c,2);assertThat(dashboard(c).get("streak").get("current").asInt()).isEqualTo(1);
    }
    @Test void futureActivityIsExcludedUntilItsActualInstant()throws Exception {
        var c=learner();clock.set("2026-09-14T17:00:01Z");complete(c,1);
        clock.set("2026-09-14T17:00:00Z");var d=dashboard(c);
        assertThat(d.get("streak").get("activeDays").asInt()).isZero();assertThat(d.get("totals").get("completedLessons").asInt()).isZero();
        assertThat(api.json(c.get("/progress/events")).get("total").asInt()).isZero();
        clock.set("2026-09-14T17:00:01Z");assertThat(dashboard(c).get("streak").get("current").asInt()).isEqualTo(1);
    }
    @Test void recommendationsChooseDueCardsThenDraftQuizThenNextLesson()throws Exception {
        var c=learner();var draft=quiz.start(c,5);var card=save(c,1);
        assertThat(dashboard(c).get("nextAction").get("path").asText()).isEqualTo("/flashcards/review");
        api.status(c.write("POST",reviewPath(card),reviewInput(card)),200);
        assertThat(dashboard(c).get("nextAction").get("path").asText()).isEqualTo("/quizzes/attempts/"+draft.get("attempt").get("id").asText());
        quiz.submit(c,quiz.fill(c,draft,5));assertThat(dashboard(c).get("nextAction").get("path").asText()).isEqualTo("/lessons/1");
        complete(c,1);assertThat(dashboard(c).get("nextAction").get("path").asText()).isEqualTo("/lessons/2");
    }
    @Test void ownershipHistoryFiltersAndTimezoneCannotBeOverridden()throws Exception {
        var a=learner();var b=learner();complete(a,1);complete(a,2);var id=owner(a);
        var me=api.json(a.get("/auth/me"));assertThat(me.get("timeZone").asText()).isEqualTo("Asia/Ho_Chi_Minh");
        api.status(a.write("PATCH","/profile",Map.of("displayName","X","timeZone","UTC")),400);
        api.status(a.write("POST","/learning-records/complete",Map.of("lessonId",3,"completedAt","2020-01-01T00:00:00Z")),400);
        api.status(a.write("POST","/progress",Map.of("streak",99)),403);
        var wrongOwner=api.json(b.get("/progress?userId="+id));assertThat(wrongOwner.get("totals").get("completedLessons").asInt()).isZero();
        assertThat(api.json(b.get("/progress/events?userId="+id)).get("total").asInt()).isZero();
        var first=api.json(a.get("/progress/events?kind=LESSON_COMPLETED&date=2026-09-14&size=1"));assertThat(first.get("total").asInt()).isEqualTo(2);assertThat(first.get("items").size()).isEqualTo(1);
        var next=api.json(a.get("/progress/events?size=1&page=1"));assertThat(next.get("items").get(0).get("id")).isNotEqualTo(first.get("items").get(0).get("id"));
        assertThat(api.json(a.get("/progress/events?kind=QUIZ_SUBMITTED")).get("total").asInt()).isZero();
        for(var query:List.of("kind=VIEW","size=0","size=51","page=-1","date=2026-02-30","date=0000-01-01","date=99999-01-01","date=bad"))api.status(a.get("/progress/events?"+query),400);
        var old=api.new Client();old.cookie=a.cookie;api.status(a.write("POST","/auth/logout",null),204);api.status(old.get("/progress"),401);
        var loggedIn=api.login(me,AdminUserApiTests.PASSWORD);assertThat(dashboard(loggedIn).get("totals").get("completedLessons").asInt()).isEqualTo(2);
        var guest=api.new Client();api.status(guest.get("/progress"),401);api.status(guest.get("/progress/events"),401);
    }
    @Test void eventFailureRollsBackEachSourceActivityAndRetryCommitsOnce()throws Exception {
        var c=learner();var owner=owner(c);var card=save(c,1);var review=reviewInput(card);var draft=quiz.fill(c,quiz.start(c,5),5);
        String constraint="test_event_failure_"+UUID.randomUUID().toString().replace("-","");
        jdbc.sql("ALTER TABLE learning_events ADD CONSTRAINT "+constraint+" CHECK(user_id <> '"+owner+"'::uuid)").update();
        try{
            api.status(c.write("POST","/learning-records/complete",Map.of("lessonId",1)),500);
            api.status(c.write("POST",reviewPath(card),review),500);
            api.status(c.write("POST",quiz.path(draft)+"/submit",Map.of("version",quiz.version(draft))),500);
            assertThat(api.json(c.get("/learning-records")).isEmpty()).isTrue();
            assertThat(api.json(c.get("/flashcards/"+card.get("id").asText())).get("version").asLong()).isZero();
            assertThat(api.json(c.get("/flashcards/reviews")).get("total").asInt()).isZero();
            assertThat(api.json(c.get(quiz.path(draft))).get("attempt").get("status").asText()).isEqualTo("IN_PROGRESS");assertThat(events(owner)).isZero();
        }finally{jdbc.sql("ALTER TABLE learning_events DROP CONSTRAINT "+constraint).update();}
        complete(c,1);api.status(c.write("POST",reviewPath(card),review),200);quiz.submit(c,draft);assertThat(events(owner)).isEqualTo(3);
    }
    @Test void concurrentCompletionsAndRetriesKeepDistinctEventsAndOneDay()throws Exception {
        var c=learner();var input=Map.of("lessonId",1);
        var results=quiz.parallel(c,"POST","/learning-records/complete",input,"POST","/learning-records/complete",input);
        for(var r:results)api.status(r,200);assertThat(events(owner(c))).isEqualTo(1);
        long thirdLesson=api.json(c.get("/lessons")).get(2).get("id").asLong();
        results=quiz.parallel(c,"POST","/learning-records/complete",Map.of("lessonId",2),"POST","/learning-records/complete",Map.of("lessonId",thirdLesson));
        for(var r:results)api.status(r,200);var d=dashboard(c);
        assertThat(d.get("totals").get("completedLessons").asInt()).isEqualTo(3);assertThat(d.get("streak").get("activeDays").asInt()).isEqualTo(1);
    }
    @Test void deletingAnAccountCascadesOnlyItsEvents()throws Exception {
        var admin=api.admin();var accountA=api.create(admin,"USER");var a=api.login(accountA,AdminUserApiTests.PASSWORD);var b=learner();
        complete(a,1);complete(b,2);var owner=owner(a);
        api.status(admin.write("DELETE","/admin/users/"+owner,null),204);assertThat(events(owner)).isZero();
        assertThat(dashboard(b).get("totals").get("completedLessons").asInt()).isEqualTo(1);api.status(a.get("/progress"),401);
    }
}
