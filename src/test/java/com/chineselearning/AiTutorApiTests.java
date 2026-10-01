package com.chineselearning;

import com.chineselearning.modules.ai.provider.TutorProvider;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"ai.gemini-api-key=contract-test-only","ai.daily-turns=3","ai.global-daily-turns=6"})
@ActiveProfiles("test")
class AiTutorApiTests {
    @LocalServerPort int port; @Autowired JdbcClient jdbc; @Autowired ObjectMapper mapper;
    @Autowired TestProvider provider; @Autowired TestClock clock;
    AdminUserApiTests api; QuizApiTests helpers; LocalDate day;
    static final AtomicInteger DAYS=new AtomicInteger();
    @TestConfiguration static class Config {
        @Bean @Primary TestProvider provider() { return new TestProvider(); }
        @Bean @Primary TestClock testClock() { return new TestClock(); }
    }
    static class TestClock extends Clock {
        Instant now; public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return Clock.fixed(now,zone);} public Instant instant(){return now;}
    }
    static class TestProvider implements TutorProvider {
        final AtomicInteger calls=new AtomicInteger(); volatile String fail; volatile String system; volatile List<Message> history;
        volatile CountDownLatch entered,release;
        public Reply generate(String s,List<Message> h) {
            calls.incrementAndGet();system=s;history=h;
            if(entered!=null) { entered.countDown();try { if(!release.await(5,TimeUnit.SECONDS))throw new Failure("TIMEOUT"); }catch(InterruptedException ex){Thread.currentThread().interrupt();throw new Failure("TIMEOUT");} }
            if(fail!=null)throw new Failure(fail);
            return new Reply("你好\nnǐ hǎo\nXin chào. Đây là dữ liệu trả lời chỉ dành cho kiểm thử.",100,30,130);
        }
    }
    @BeforeEach void setup() {
        day=LocalDate.of(2090,1,1).plusDays(DAYS.incrementAndGet()*3L);clock.now=day.atTime(16,59,59).toInstant(ZoneOffset.UTC);
        provider.calls.set(0);provider.fail=null;provider.entered=null;provider.release=null;
        api=new AdminUserApiTests();api.port=port;api.jdbc=jdbc;api.mapper=mapper;
        helpers=new QuizApiTests();helpers.api=api;
    }
    @AfterEach void cleanup() {
        if(provider.release!=null)provider.release.countDown();api.cleanup();
        jdbc.sql("DELETE FROM ai_global_usage WHERE study_date BETWEEN :start AND :end").param("start",day).param("end",day.plusDays(1)).update();
    }
    AdminUserApiTests.Client learner() throws Exception { return helpers.learner(); }
    JsonNode start(AdminUserApiTests.Client c,Long lesson)throws Exception {
        var payload=new HashMap<String,Object>();payload.put("requestId",UUID.randomUUID());payload.put("lessonId",lesson);
        var r=c.write("POST","/ai/conversations",payload);api.status(r,200);return api.json(r);
    }
    String path(JsonNode d){return "/ai/conversations/"+d.get("conversation").get("id").asText();}
    Map<String,Object> input(String question){return Map.of("requestId",UUID.randomUUID(),"question",question);}
    JsonNode send(AdminUserApiTests.Client c,String path,Map<String,Object> input)throws Exception {
        var r=c.write("POST",path+"/turns",input);api.status(r,200);return api.json(r);
    }
    @Test void conversationsPersistOwnerContextAndIdempotentRequestsWithoutAffectingStreak()throws Exception {
        var c=learner();var key=UUID.randomUUID();var body=Map.of("requestId",key,"lessonId",1);
        var d=api.json(c.write("POST","/ai/conversations",body));String path=path(d);
        assertThat(api.json(c.write("POST","/ai/conversations",body))).isEqualTo(d);
        api.status(c.write("POST","/ai/conversations",Map.of("requestId",key,"lessonId",2)),409);
        var input=input("Giải thích 你好");var first=send(c,path,input);
        assertThat(first.get("status").asText()).isEqualTo("SUCCEEDED");assertThat(first.get("totalTokens").asInt()).isEqualTo(130);
        assertThat(send(c,path,input)).isEqualTo(first);assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(provider.system).contains("你好","nǐ hǎo","DỮ LIỆU BÀI HỌC");
        send(c,path,input("Cho mình ví dụ"));assertThat(provider.history).hasSize(3);
        assertThat(provider.history.get(0).text()).isEqualTo("Giải thích 你好");
        assertThat(api.json(c.get(path)).get("turns").size()).isEqualTo(2);
        assertThat(api.json(c.get("/progress")).get("streak").get("activeDays").asInt()).isZero();
        var resumed=api.new Client();resumed.cookie=c.cookie;assertThat(api.json(resumed.get(path))).isEqualTo(api.json(c.get(path)));
        api.status(c.write("POST","/auth/logout",null),204);api.status(resumed.get(path),401);
    }
    @Test void learningModePersistsAndRenameDoesNotChangeProviderContextOrOwnership()throws Exception {
        var a=learner();var b=learner();var key=UUID.randomUUID();
        var body=Map.of("requestId",key,"lessonId",1,"mode","CONVERSATION");
        var created=a.write("POST","/ai/conversations",body);api.status(created,200);
        var detail=api.json(created);String path=path(detail);
        assertThat(detail.get("conversation").get("mode").asText()).isEqualTo("CONVERSATION");
        assertThat(api.json(a.write("POST","/ai/conversations",body))).isEqualTo(detail);
        api.status(a.write("POST","/ai/conversations",Map.of("requestId",key,"lessonId",1,"mode","CORRECT")),409);
        api.status(a.write("POST","/ai/conversations",Map.of("requestId",UUID.randomUUID(),"mode","INVALID")),400);
        api.status(a.raw("PATCH",path,Map.of("title","Luyện chào hỏi"),null),403);
        api.status(b.write("PATCH",path,Map.of("title","Không được đổi")),404);
        api.status(a.write("PATCH",path,Map.of("title"," ")),400);
        api.status(a.write("PATCH",path,Map.of("title","x".repeat(121))),400);
        api.status(a.write("PATCH",path,Map.of("title","  Luyện chào hỏi  ")),200);
        assertThat(api.json(a.get(path)).get("conversation").get("title").asText()).isEqualTo("Luyện chào hỏi");
        assertThat(a.get("/ai/conversations").body()).contains("Luyện chào hỏi","CONVERSATION");
        send(a,path,input("Cùng mình chào hỏi"));
        assertThat(provider.system).contains("CHẾ ĐỘ HỘI THOẠI","Mỗi lượt chỉ hỏi một câu","你好");
        assertThat(provider.system).doesNotContain("Luyện chào hỏi");
        assertThat(api.json(a.get("/ai/status")).get("usedToday").asInt()).isEqualTo(1);
    }
    @Test void ownershipValidationAndDeletionProtectDataAndDoNotRefundQuota()throws Exception {
        var a=learner();var b=learner();var d=start(a,null);var path=path(d);var payload=input("学校 nghĩa là gì?");
        api.status(b.get(path),404);api.status(b.write("DELETE",path,null),404);api.status(b.write("POST",path+"/turns",payload),404);
        assertThat(api.json(b.get("/ai/conversations")).get("total").asInt()).isZero();
        api.status(a.raw("POST",path+"/turns",payload,null),403);
        for(var bad:List.of(Map.of("requestId",UUID.randomUUID(),"question"," "),Map.of("requestId",UUID.randomUUID(),"question","a".repeat(1501)),Map.of("requestId",UUID.randomUUID(),"question","你好","userId",UUID.randomUUID())))
            api.status(a.write("POST",path+"/turns",bad),400);
        for(var query:List.of("page=-1","size=0","size=51"))api.status(a.get("/ai/conversations?"+query),400);
        var t=send(a,path,payload);var changed=new HashMap<>(payload);changed.put("question","changed");api.status(a.write("POST",path+"/turns",changed),409);
        var other=start(a,null);api.status(a.write("POST",path(other)+"/turns",payload),409);
        assertThat(a.get(path).body()).doesNotContain("contract-test-only","contextText","userId");
        var bOwn=start(b,null);send(b,path(bOwn),input("谢谢"));
        api.status(a.write("DELETE",path,null),204);api.status(a.get(path),404);
        assertThat(api.json(a.get("/ai/status")).get("usedToday").asInt()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_turns WHERE id=:id").param("id",UUID.fromString(t.get("id").asText())).query(Integer.class).single()).isZero();
        assertThat(api.json(b.get(path(bOwn))).get("turns").size()).isEqualTo(1);
    }
    @Test void failedCallsAreStoredNoAutomaticRetryAndOnlyOneExplicitRetryIsAllowed()throws Exception {
        var c=learner();var d=start(c,null);var path=path(d);provider.fail="RATE_LIMITED";var input=input("我喜欢茶 对吗?");
        var failed=send(c,path,input);assertThat(failed.get("status").asText()).isEqualTo("FAILED");assertThat(failed.get("answer").isNull()).isTrue();
        assertThat(send(c,path,input)).isEqualTo(failed);assertThat(provider.calls.get()).isEqualTo(1);
        var retry=Map.<String,Object>of("requestId",UUID.randomUUID(),"question",input.get("question"),"retryOf",failed.get("id").asText());
        var second=send(c,path,retry);assertThat(second.get("status").asText()).isEqualTo("FAILED");assertThat(provider.calls.get()).isEqualTo(2);
        api.status(c.write("POST",path+"/turns",Map.of("requestId",UUID.randomUUID(),"question",input.get("question"),"retryOf",second.get("id").asText())),409);
        assertThat(api.json(c.get("/ai/status")).get("usedToday").asInt()).isEqualTo(2);
        provider.fail=null;send(c,path,input("Một câu khác"));assertThat(provider.history).hasSize(1);
    }
    @Test void dailyQuotaUsesVietnamMidnightAndCannotBeResetByDeletingConversation()throws Exception {
        var c=learner();var path=path(start(c,null));
        for(int i=0;i<3;i++)send(c,path,input("Câu "+i));
        assertThat(api.json(c.get("/ai/status")).get("remainingToday").asInt()).isZero();
        assertThat(api.json(c.get("/ai/status")).get("reason").asText()).isEqualTo("DAILY_LIMIT");
        api.status(c.write("POST",path+"/turns",input("Hết lượt")),429);assertThat(provider.calls.get()).isEqualTo(3);
        api.status(c.write("DELETE",path,null),204);var another=path(start(c,null));api.status(c.write("POST",another+"/turns",input("Không hoàn lại")),429);
        clock.now=clock.now.plusSeconds(1);assertThat(api.json(c.get("/ai/status")).get("remainingToday").asInt()).isEqualTo(3);
        send(c,another,input("Ngày mới"));assertThat(api.json(c.get("/ai/status")).get("today").asText()).isEqualTo(day.plusDays(1).toString());
    }
    @Test void concurrentDuplicateRequestsDispatchOnceAndOtherConversationsWait()throws Exception {
        var c=learner();var path=path(start(c,null));var other=path(start(c,null));
        provider.entered=new CountDownLatch(1);provider.release=new CountDownLatch(1);var input=input("你好");
        var executor=Executors.newSingleThreadExecutor();
        try {
            var pending=executor.submit(() -> send(c,path,input));assertThat(provider.entered.await(3,TimeUnit.SECONDS)).isTrue();
            var duplicate=send(c,path,input);assertThat(duplicate.get("status").asText()).isEqualTo("PENDING");
            api.status(c.write("POST",other+"/turns",input("其他")),409);api.status(c.write("DELETE",path,null),409);
            provider.release.countDown();var complete=pending.get(4,TimeUnit.SECONDS);assertThat(complete.get("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(send(c,path,input)).isEqualTo(complete);assertThat(provider.calls.get()).isEqualTo(1);
        }finally {provider.release.countDown();executor.shutdownNow();}
    }
    @Test void expiredPendingRequestRecoversAfterRestartWithoutDispatchingOldWork()throws Exception {
        var c=learner();var path=path(start(c,null));provider.entered=new CountDownLatch(1);provider.release=new CountDownLatch(1);var input=input("你好");
        var executor=Executors.newSingleThreadExecutor();
        try {
            var pending=executor.submit(() -> send(c,path,input));assertThat(provider.entered.await(3,TimeUnit.SECONDS)).isTrue();clock.now=clock.now.plusSeconds(61);
            var data=api.json(c.get(path));assertThat(data.get("turns").get(0).get("status").asText()).isEqualTo("FAILED");
            provider.release.countDown();assertThat(pending.get(4,TimeUnit.SECONDS).get("errorCode").asText()).isEqualTo("TIMEOUT");
            assertThat(send(c,path,input).get("status").asText()).isEqualTo("FAILED");assertThat(provider.calls.get()).isEqualTo(1);
        }finally{provider.release.countDown();executor.shutdownNow();}
    }
    @Test void globalBudgetAppliesAcrossAccountsAndAccountDeletionKeepsGlobalCharges()throws Exception {
        var a=learner();var b=learner();var c=learner();var aPath=path(start(a,null));var bPath=path(start(b,null));var cPath=path(start(c,null));
        for(int i=0;i<3;i++){send(a,aPath,input("a"+i));send(b,bPath,input("b"+i));}
        assertThat(api.json(c.get("/ai/status")).get("reason").asText()).isEqualTo("GLOBAL_LIMIT");
        api.status(c.write("POST",cPath+"/turns",input("Over global")),429);assertThat(provider.calls.get()).isEqualTo(6);
        var owner=UUID.fromString(api.json(a.get("/auth/me")).get("id").asText());
        jdbc.sql("DELETE FROM users WHERE id=:id").param("id",owner).update();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM ai_turns WHERE user_id=:id").param("id",owner).query(Integer.class).single()).isZero();
        api.status(c.write("POST",cPath+"/turns",input("Still capped")),429);assertThat(api.json(b.get(bPath)).get("turns").size()).isEqualTo(3);
    }
}
