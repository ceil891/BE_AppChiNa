package com.chineselearning;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FoundationApiTests {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    AdminUserApiTests api;
    List<String> slugs=new ArrayList<>();
    @BeforeEach void setup() { api=new AdminUserApiTests();api.port=port;api.jdbc=jdbc;api.mapper=mapper; }
    @AfterEach void cleanup() { api.cleanup();for(var slug:slugs)jdbc.sql("DELETE FROM foundation_lessons WHERE slug=:slug").param("slug",slug).update(); }
    Map<String,Object> body(String slug,long version,int correct) {
        return new HashMap<>(Map.of("position",1,"version",version,"content",new HashMap<>(Map.of(
            "slug",slug,"title","Bài test Pinyin","subtitle","Nội dung thử","minutes",5,"symbol","拼","toneChart",false,
            "sections",List.of(Map.of("title","Phần đầu","text","Học phát âm"),Map.of("title","Phần sau","text","Tự kiểm tra")),
            "groups",List.of(Map.of("title","Vận mẫu","sounds","a o e","note","Đọc chậm")),
            "examples",List.of(Map.of("symbol","a","pinyin","mā","hanzi","妈","meaning","mẹ","tip","Thanh 1","audioSrc","/audio/foundations/ma1.mp3")),
            "questions",List.of(Map.of("prompt","Thanh nào?","options",List.of("Thanh 1","Thanh 2"),"correct",correct,"explanation","Giải thích gốc"))))));
    }
    String create(AdminUserApiTests.Client admin) throws Exception {
        String slug="aaa-test-"+UUID.randomUUID();slugs.add(slug);
        api.status(admin.write("POST","/admin/foundations",body(slug,0,0)),201);return slug;
    }
    Map<String,Object> submit(long version,UUID request,int answer) { return Map.of("lessonVersion",version,"requestId",request,"answers",List.of(answer)); }

    @Test void completePinyinCurriculumIsRequiredForOtherLearningApis() throws Exception {
        var admin=api.admin();var account=api.create(admin,"USER");
        var learner=api.login(account,AdminUserApiTests.PASSWORD);
        var other=api.login(api.create(admin,"USER"),AdminUserApiTests.PASSWORD);
        var endpoints=List.of("/courses","/courses/1","/lessons","/lessons/1","/vocabulary","/vocabulary/1",
            "/learning-records","/flashcards","/quizzes/sets","/skills/exercises","/ai/status");
        for(var path:endpoints)api.status(learner.get(path),403);
        api.status(api.new Client().get("/lessons"),403);
        api.status(learner.write("POST","/learning-records/complete",Map.of("lessonId",1)),403);
        api.status(learner.write("POST","/flashcards",Map.of("vocabularyId",1)),403);
        api.status(learner.write("POST","/quizzes/attempts",Map.of()),403);
        api.status(learner.write("POST","/skills/attempts",Map.of()),403);
        api.status(learner.write("POST","/ai/conversations",Map.of()),403);
        api.status(learner.get("/profile"),200);api.status(learner.get("/progress"),200);
        api.status(admin.get("/admin/lessons"),200);
        var cards=api.json(learner.get("/foundations"));assertThat(cards.size()).isGreaterThan(1);
        for(int index=0;index<cards.size();index++) {
            String slug=cards.get(index).get("slug").asText();
            var lesson=api.json(learner.get("/foundations/"+slug));
            var answers=new ArrayList<Integer>();for(var question:lesson.get("questions"))answers.add(0);
            api.status(learner.write("POST","/foundations/"+slug+"/check",Map.of("lessonVersion",lesson.get("version").asLong(),"requestId",UUID.randomUUID(),"answers",answers)),200);
            if(index<cards.size()-1)api.status(learner.get("/lessons"),403);
        }
        for(var path:List.of("/courses","/lessons","/vocabulary","/learning-records","/flashcards","/quizzes/sets","/skills/exercises","/ai/status"))api.status(learner.get(path),200);
        for(var path:endpoints)api.status(other.get(path),403);
        api.status(api.login(account,AdminUserApiTests.PASSWORD).get("/lessons"),200);
        assertThat(api.json(learner.get("/roadmap")).toString()).doesNotContain("LOCKED");
    }

    @Test void sequentialLessonsCannotBeBypassedByUrlBookmarkOrSubmission() throws Exception {
        var admin=api.admin();
        for(int i=0;i<3;i++) {
            String slug="aaa-"+i+"-"+UUID.randomUUID();slugs.add(slug);
            api.status(admin.write("POST","/admin/foundations",body(slug,0,0)),201);
            api.status(admin.write("PATCH","/admin/foundations/"+slug+"/publication",Map.of("version",0,"published",true)),200);
        }
        String first=slugs.get(0),second=slugs.get(1),third=slugs.get(2);
        var learner=api.login(api.create(admin,"USER"),AdminUserApiTests.PASSWORD);
        var other=api.login(api.create(admin,"USER"),AdminUserApiTests.PASSWORD);
        var guest=api.new Client();
        assertThat(api.json(learner.get("/foundations")).get(0).get("locked").asBoolean()).isFalse();
        assertThat(api.json(learner.get("/foundations")).get(1).get("locked").asBoolean()).isTrue();
        assertThat(api.json(learner.get("/roadmap")).get(1).get("status").asText()).isEqualTo("LOCKED");
        api.status(learner.get("/foundations/"+second),403);
        api.status(guest.get("/foundations/"+second),403);
        api.status(guest.write("POST","/foundations/"+second+"/check",submit(1,UUID.randomUUID(),0)),403);
        api.status(learner.write("POST","/foundations/"+second+"/check",submit(1,UUID.randomUUID(),0)),403);
        api.status(learner.write("PUT","/foundation-progress/"+second,Map.of("lessonVersion",1,"sectionIndex",0,"version",0)),403);
        assertThat(learner.get("/foundation-progress").body()).isEqualTo("[]");
        api.status(learner.write("PUT","/foundation-progress/"+first,Map.of("lessonVersion",1,"sectionIndex",1,"version",0)),200);
        api.status(learner.get("/foundations/"+second),403);
        api.status(learner.write("POST","/foundations/"+first+"/check",submit(1,UUID.randomUUID(),5)),400);
        api.status(learner.get("/foundations/"+second),403);
        api.status(learner.write("POST","/foundations/"+first+"/check",submit(1,UUID.randomUUID(),0)),200);
        api.status(learner.get("/foundations/"+second),200);
        api.status(learner.get("/foundations/"+third),403);
        api.status(other.get("/foundations/"+second),403);
        assertThat(api.json(learner.get("/foundations")).get(1).get("locked").asBoolean()).isFalse();
        assertThat(api.json(learner.get("/roadmap")).get(1).get("status").asText()).isEqualTo("NEW");
        api.status(learner.write("POST","/foundations/"+second+"/check",submit(1,UUID.randomUUID(),0)),200);
        api.status(learner.get("/foundations/"+third),200);
        // Unpublished predecessors do not block the active roadmap.
        api.status(admin.write("PATCH","/admin/foundations/"+first+"/publication",Map.of("version",1,"published",false)),200);
        api.status(other.get("/foundations/"+second),200);
        api.status(other.get("/foundations/"+third),403);
    }

    @Test void privateProgressServerScoreReplayAndBestScoreSurviveLogin() throws Exception {
        var admin=api.admin();String slug=create(admin);String base="/foundations/"+slug;
        api.status(admin.write("PATCH","/admin/foundations/"+slug+"/publication",Map.of("version",0,"published",true)),200);
        var learnerEmail=api.create(admin,"USER");var otherEmail=api.create(admin,"USER");
        var learner=api.login(learnerEmail,AdminUserApiTests.PASSWORD);var other=api.login(otherEmail,AdminUserApiTests.PASSWORD);
        var publicLesson=learner.get(base);api.status(publicLesson,200);assertThat(publicLesson.body()).doesNotContain("correct","explanation");
        var bookmark=Map.of("lessonVersion",1,"sectionIndex",1,"version",0);
        api.status(learner.write("PUT","/foundation-progress/"+slug,bookmark),200);
        api.status(learner.write("PUT","/foundation-progress/"+slug,bookmark),409);
        assertThat(other.get("/foundation-progress").body()).isEqualTo("[]");
        assertThat(api.json(learner.get("/progress")).get("streak").get("activeDays").asInt()).isZero();
        var request=submit(1,UUID.randomUUID(),0);
        var first=learner.write("POST",base+"/check",request);api.status(first,200);
        assertThat(api.json(first).get("score").asInt()).isEqualTo(100);assertThat(api.json(first).get("saved").asBoolean()).isTrue();
        var replay=learner.write("POST",base+"/check",request);api.status(replay,200);assertThat(api.json(replay)).isEqualTo(api.json(first));
        var changed=new HashMap<>(request);changed.put("answers",List.of(1));api.status(learner.write("POST",base+"/check",changed),409);
        api.status(learner.write("POST",base+"/check",submit(1,UUID.randomUUID(),1)),200);
        var history=api.json(learner.get("/progress/events?kind=FOUNDATION_SUBMITTED"));
        assertThat(history.get("total").asInt()).isEqualTo(2);
        var dashboard=api.json(learner.get("/progress"));
        assertThat(dashboard.get("streak").get("activeDays").asInt()).isEqualTo(1);
        assertThat(dashboard.get("streak").get("studiedToday").asBoolean()).isTrue();
        assertThat(dashboard.get("todayActivity").get("totalActivities").asInt()).isEqualTo(2);
        assertThat(api.json(other.get("/progress/events?kind=FOUNDATION_SUBMITTED")).get("total").asInt()).isZero();
        var fresh=api.login(learnerEmail,AdminUserApiTests.PASSWORD);var p=api.json(fresh.get("/foundation-progress")).get(0);
        assertThat(p.get("attempts").asInt()).isEqualTo(2);assertThat(p.get("bestScore").asInt()).isEqualTo(100);assertThat(p.get("lastScore").asInt()).isZero();
        assertThat(p.get("completedAt").isNull()).isFalse();assertThat(p.get("sectionIndex").asInt()).isEqualTo(1);
        assertThat(other.get("/foundation-progress").body()).isEqualTo("[]");
        api.status(learner.write("POST",base+"/check",Map.of("lessonVersion",1,"requestId",UUID.randomUUID(),"answers",List.of(0),"score",100)),400);
        api.status(learner.write("POST",base+"/check",submit(1,UUID.randomUUID(),5)),400);
        api.status(learner.raw("PUT","/foundation-progress/"+slug,bookmark,null),403);
        api.status(admin.get("/foundation-progress"),403);
    }

    @Test void publicationConflictsAndHistoricalResultsDoNotChangeWithEditorialUpdates() throws Exception {
        var admin=api.admin();String slug=create(admin),editor="/admin/foundations/"+slug,base="/foundations/"+slug;
        var learner=api.login(api.create(admin,"USER"),AdminUserApiTests.PASSWORD);
        api.status(learner.get(base),404);assertThat(learner.get("/foundations").body()).doesNotContain(slug);
        api.status(admin.write("PATCH",editor+"/publication",Map.of("version",0,"published",true)),200);
        api.status(admin.write("PUT",editor,body(slug,1,1)),409);
        var request=submit(1,UUID.randomUUID(),0);var result=learner.write("POST",base+"/check",request);api.status(result,200);
        api.status(admin.write("PATCH",editor+"/publication",Map.of("version",1,"published",false)),200);
        api.status(learner.get(base),404);api.status(learner.write("POST",base+"/check",submit(1,UUID.randomUUID(),0)),404);
        api.status(admin.write("PUT",editor,body(slug,1,1)),409);
        api.status(admin.write("PUT",editor,body(slug,2,1)),200);
        var replay=learner.write("POST",base+"/check",request);api.status(replay,200);assertThat(api.json(replay)).isEqualTo(api.json(result));
        api.status(admin.write("PATCH",editor+"/publication",Map.of("version",3,"published",true)),200);
        api.status(learner.write("POST",base+"/check",submit(1,UUID.randomUUID(),0)),409);
        var old=api.json(learner.get("/foundation-progress")).get(0).get("lastResult");assertThat(old.get("score").asInt()).isEqualTo(100);assertThat(old.get("questions").get(0).get("correct").asInt()).isZero();
        var latest=learner.write("POST",base+"/check",submit(4,UUID.randomUUID(),0));api.status(latest,200);assertThat(api.json(latest).get("score").asInt()).isZero();
    }

    @Test void readOnlyPermissionAndInputValidationProtectEditorialContent() throws Exception {
        var admin=api.admin();String slug=create(admin),base="/admin/foundations/"+slug;
        var learner=api.login(api.create(admin,"USER"),AdminUserApiTests.PASSWORD);
        api.status(learner.get(base),403);api.status(learner.write("PUT",base,body(slug,0,0)),403);
        api.status(admin.raw("PUT",base,body(slug,0,0),null),403);
        api.status(admin.write("POST","/admin/foundations",body(slug,0,0)),409);
        api.status(admin.write("PUT",base,body(slug,0,5)),400);
        var invalid=body(slug,0,0);
        @SuppressWarnings("unchecked") var content=(Map<String,Object>)invalid.get("content");
        content.put("examples",List.of(Map.of("symbol","a","pinyin","a","hanzi","啊","meaning","a","tip","Đọc","audioSrc","javascript:alert(1)")));
        api.status(admin.write("PUT",base,invalid),400);
        content.put("sections",List.of());api.status(admin.write("PUT",base,invalid),400);
        String role="FOUNDATION_"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
        try {
            api.status(admin.write("POST","/admin/roles",Map.of("code",role,"name","Pinyin reader","description","Test","permissions",List.of("foundations.read"))),201);
            var reader=api.login(api.create(admin,role),AdminUserApiTests.PASSWORD);
            api.status(reader.get(base),200);api.status(reader.write("PUT",base,body(slug,0,0)),403);
            api.status(reader.write("PATCH",base+"/publication",Map.of("version",0,"published",true)),403);
        } finally { api.cleanup();jdbc.sql("DELETE FROM roles WHERE code=:role").param("role",role).update(); }
    }

    @Test void guestsCanPracticeAndConcurrentRetriesCreateOneSavedResult() throws Exception {
        var admin=api.admin();String slug=create(admin),path="/foundations/"+slug+"/check";
        api.status(admin.write("PATCH","/admin/foundations/"+slug+"/publication",Map.of("version",0,"published",true)),200);
        var guest=api.new Client();api.status(guest.get("/foundations/"+slug),200);
        var trial=guest.write("POST",path,submit(1,UUID.randomUUID(),0));api.status(trial,200);
        assertThat(api.json(trial).get("saved").asBoolean()).isFalse();api.status(guest.get("/foundation-progress"),401);
        api.status(guest.raw("POST",path,submit(1,UUID.randomUUID(),0),null),403);
        var account=api.create(admin,"USER");var first=api.login(account,AdminUserApiTests.PASSWORD);var second=api.login(account,AdminUserApiTests.PASSWORD);
        var input=submit(1,UUID.randomUUID(),0);String tokenA=first.csrf(),tokenB=second.csrf();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var gate=new java.util.concurrent.CountDownLatch(1);
        try {
            var a=pool.submit(()->{gate.await();return first.raw("POST",path,input,tokenA);});
            var b=pool.submit(()->{gate.await();return second.raw("POST",path,input,tokenB);});gate.countDown();
            var ra=a.get(15,java.util.concurrent.TimeUnit.SECONDS);var rb=b.get(15,java.util.concurrent.TimeUnit.SECONDS);
            api.status(ra,200);api.status(rb,200);assertThat(api.json(ra)).isEqualTo(api.json(rb));
            assertThat(api.json(first.get("/foundation-progress")).get(0).get("attempts").asInt()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
}
