package com.chineselearning;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(FlashcardApiTests.TestClockConfig.class)
class FlashcardApiTests {
    static final Instant NOW=Instant.parse("2026-09-14T08:00:00Z");
    @TestConfiguration static class TestClockConfig {
        @Bean @Primary Clock fixedReviewClock(){return Clock.fixed(NOW,ZoneOffset.UTC);}
    }
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    AdminUserApiTests api;
    final List<Long> words=new ArrayList<>();
    @BeforeEach void setup(){api=new AdminUserApiTests();api.port=port;api.jdbc=jdbc;api.mapper=mapper;}
    @AfterEach void cleanup(){api.cleanup();for(long id:words)jdbc.sql("DELETE FROM vocabulary WHERE id=:id").param("id",id).update();}
    AdminUserApiTests.Client learner()throws Exception{
        var client=api.new Client();String email=api.email();
        api.status(client.write("POST","/auth/register",Map.of("email",email,"password",AdminUserApiTests.PASSWORD,"displayName","Flashcard test")),201);
        api.status(client.write("POST","/auth/login",Map.of("email",email,"password",AdminUserApiTests.PASSWORD)),200);return client;
    }
    JsonNode save(AdminUserApiTests.Client client,long word)throws Exception{
        var result=client.write("POST","/flashcards",Map.of("vocabularyId",word));api.status(result,200);return api.json(result);
    }
    Map<String,Object> answer(JsonNode card,String rating,UUID request){return Map.of("requestId",request,"rating",rating,"version",card.get("version").asLong());}
    String path(JsonNode card){return "/flashcards/"+card.get("id").asText();}
    @Test void saveAndBulkSaveAreIdempotentAndNeverResetExistingSchedule()throws Exception{
        var client=learner();var card=save(client,1);
        assertThat(save(client,1)).isEqualTo(card);
        assertThat(api.json(client.get("/flashcards/by-word/1")).get("card")).isEqualTo(card);
        assertThat(api.json(client.get("/flashcards?dueOnly=true")).get("total").asInt()).isEqualTo(1);
        api.status(client.write("POST",path(card)+"/reviews",answer(card,"REMEMBER",UUID.randomUUID())),200);
        var scheduled=api.json(client.get(path(card)));
        assertThat(save(client,1)).isEqualTo(scheduled);
        api.status(client.write("POST","/flashcards/lessons/1",null),200);
        api.status(client.write("POST","/flashcards/lessons/1",null),200);
        assertThat(api.json(client.get(path(card)))).isEqualTo(scheduled);
        var all=api.json(client.get("/flashcards?lessonId=1&size=3"));
        assertThat(all.get("items").size()).isEqualTo(3);assertThat(all.get("total").asInt()).isEqualTo(10);
        assertThat(all.get("stats").get("due").asInt()).isEqualTo(9);
        assertThat(api.json(client.get("/flashcards?lessonId=1&dueOnly=true")).get("total").asInt()).isEqualTo(9);
        assertThat(api.json(client.get("/flashcards?lessonId=2")).get("total").asInt()).isZero();
        assertThat(api.json(client.get("/flashcards?page=100")).get("items").size()).isZero();
    }
    @Test void reviewPersistsAcrossReloadAndReplayDoesNotCountTwice()throws Exception{
        var client=learner();var card=save(client,1);var payload=answer(card,"REMEMBER",UUID.randomUUID());
        var first=client.write("POST",path(card)+"/reviews",payload);api.status(first,200);
        var result=api.json(first);
        assertThat(OffsetDateTime.parse(result.get("nextReviewAt").asText()).toInstant()).isEqualTo(NOW.plusSeconds(86400));
        assertThat(result.get("reviewCount").asInt()).isEqualTo(1);
        var reloaded=api.new Client();reloaded.cookie=client.cookie;
        assertThat(api.json(reloaded.write("POST",path(card)+"/reviews",payload))).isEqualTo(result);
        assertThat(api.json(reloaded.get(path(card))).get("reviewCount").asInt()).isEqualTo(1);
        var due=api.json(reloaded.get("/flashcards?dueOnly=true"));
        assertThat(due.get("total").asInt()).isZero();
        assertThat(due.get("stats").get("reviewed").asInt()).isEqualTo(1);
        assertThat(api.json(reloaded.get("/flashcards/reviews")).get("total").asInt()).isEqualTo(1);
        api.status(reloaded.write("POST",path(card)+"/reviews",answer(card,"AGAIN",UUID.fromString(result.get("requestId").asText()))),409);
        var freshCard=api.json(reloaded.get(path(card)));
        api.status(reloaded.write("POST",path(card)+"/reviews",answer(freshCard,"AGAIN",UUID.randomUUID())),409);
        api.status(client.write("POST","/auth/logout",null),204);
        api.status(reloaded.get("/flashcards"),401);
    }
    @Test void dueBoundaryAndAgainScheduleUseServerTime()throws Exception{
        var client=learner();var card=save(client,1);
        assertThat(OffsetDateTime.parse(card.get("nextReviewAt").asText()).toInstant()).isEqualTo(NOW);
        var future=save(client,2);
        jdbc.sql("UPDATE user_vocabulary SET next_review_at=:time WHERE id=:id")
            .param("time",OffsetDateTime.ofInstant(NOW.plusNanos(1000),ZoneOffset.UTC)).param("id",UUID.fromString(future.get("id").asText())).update();
        var due=api.json(client.get("/flashcards?dueOnly=true"));
        assertThat(due.get("items").size()).isEqualTo(1);assertThat(due.get("items").get(0).get("id")).isEqualTo(card.get("id"));
        api.status(client.write("POST",path(future)+"/reviews",answer(future,"REMEMBER",UUID.randomUUID())),409);
        var reviewed=client.write("POST",path(card)+"/reviews",answer(card,"AGAIN",UUID.randomUUID()));api.status(reviewed,200);
        assertThat(OffsetDateTime.parse(api.json(reviewed).get("nextReviewAt").asText()).toInstant()).isEqualTo(NOW.plusSeconds(600));
        assertThat(api.json(reviewed).get("successStreak").asInt()).isZero();
    }
    @Test void twoAccountsCannotReadReviewOrArchiveEachOthersCards()throws Exception{
        var a=learner();var b=learner();var cardA=save(a,1);var cardB=save(b,1);
        assertThat(cardA.get("id")).isNotEqualTo(cardB.get("id"));
        api.status(b.get(path(cardA)),404);
        api.status(b.write("DELETE",path(cardA),null),404);
        var request=UUID.randomUUID();
        api.status(a.write("POST",path(cardA)+"/reviews",answer(cardA,"REMEMBER",request)),200);
        api.status(b.write("POST",path(cardA)+"/reviews",answer(cardA,"REMEMBER",request)),404);
        assertThat(api.json(b.get("/flashcards/reviews")).get("total").asInt()).isZero();
        assertThat(b.get("/flashcards").body()).doesNotContain(cardA.get("id").asText());
        assertThat(a.get("/flashcards").body()).doesNotContain(cardB.get("id").asText());
        // Idempotency keys are scoped to the owner, not shared between learners.
        api.status(b.write("POST",path(cardB)+"/reviews",answer(cardB,"AGAIN",request)),200);
        assertThat(api.json(b.get("/flashcards/reviews")).get("items").get(0).get("rating").asText()).isEqualTo("AGAIN");
    }
    @Test void concurrentRetriesAndStaleTabsCannotDoubleCount()throws Exception{
        var client=learner();var card=save(client,1);var payload=answer(card,"REMEMBER",UUID.randomUUID());
        var results=parallel(client,path(card)+"/reviews",payload,path(card)+"/reviews",payload);
        assertThat(results).containsExactlyInAnyOrder(200,200);
        assertThat(api.json(client.get("/flashcards/reviews")).get("total").asInt()).isEqualTo(1);
        var another=save(client,2);
        results=parallel(client,path(another)+"/reviews",answer(another,"REMEMBER",UUID.randomUUID()),path(another)+"/reviews",answer(another,"AGAIN",UUID.randomUUID()));
        assertThat(results).containsExactlyInAnyOrder(200,409);
        assertThat(api.json(client.get(path(another))).get("reviewCount").asInt()).isEqualTo(1);
        var third=save(client,3);var fourth=save(client,4);var shared=UUID.randomUUID();
        results=parallel(client,path(third)+"/reviews",answer(third,"REMEMBER",shared),path(fourth)+"/reviews",answer(fourth,"AGAIN",shared));
        assertThat(results).containsExactlyInAnyOrder(200,409);
        int total=api.json(client.get(path(third))).get("reviewCount").asInt()+api.json(client.get(path(fourth))).get("reviewCount").asInt();
        assertThat(total).isEqualTo(1);
        assertThat(api.json(client.get("/flashcards/reviews")).get("total").asInt()).isEqualTo(3);
    }
    List<Integer> parallel(AdminUserApiTests.Client client,String firstPath,Object firstBody,String secondPath,Object secondBody)throws Exception{
        var a=api.new Client();a.cookie=client.cookie;var b=api.new Client();b.cookie=client.cookie;
        String csrf=client.csrf();var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try{
            var first=executor.submit(()->{start.await();return a.raw("POST",firstPath,firstBody,csrf).statusCode();});
            var second=executor.submit(()->{start.await();return b.raw("POST",secondPath,secondBody,csrf).statusCode();});
            start.countDown();return List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
        }finally{executor.shutdownNow();}
    }
    @Test void archiveAndResavePreserveHistoryScheduleAndInvalidateOldCardVersion()throws Exception{
        var client=learner();var card=save(client,1);var payload=answer(card,"AGAIN",UUID.randomUUID());
        var review=api.json(client.write("POST",path(card)+"/reviews",payload));
        api.status(client.write("DELETE",path(card),null),204);api.status(client.write("DELETE",path(card),null),204);
        api.status(client.get(path(card)),404);
        assertThat(api.json(client.get("/flashcards/by-word/1")).get("card").isNull()).isTrue();
        assertThat(api.json(client.get("/flashcards")).get("total").asInt()).isZero();
        assertThat(api.json(client.get("/flashcards/reviews")).get("total").asInt()).isEqualTo(1);
        assertThat(api.json(client.write("POST",path(card)+"/reviews",payload))).isEqualTo(review);
        api.status(client.write("POST",path(card)+"/reviews",answer(card,"AGAIN",UUID.randomUUID())),404);
        var restored=save(client,1);
        assertThat(restored.get("id")).isEqualTo(card.get("id"));assertThat(restored.get("reviewCount").asInt()).isEqualTo(1);
        assertThat(restored.get("nextReviewAt")).isEqualTo(review.get("nextReviewAt"));
        assertThat(restored.get("version").asLong()).isEqualTo(3);
        api.status(client.write("POST",path(card)+"/reviews",answer(card,"REMEMBER",UUID.randomUUID())),409);
    }
    @Test void authenticationCsrfAndInputValidationRemainEnforced()throws Exception{
        var guest=api.new Client();api.status(guest.get("/flashcards"),401);api.status(guest.get("/flashcards/reviews"),401);
        api.status(guest.write("POST","/flashcards",Map.of("vocabularyId",1)),401);
        var client=learner();var card=save(client,1);
        api.status(client.raw("POST",path(card)+"/reviews",answer(card,"REMEMBER",UUID.randomUUID()),null),403);
        api.status(client.write("POST","/flashcards",Map.of("vocabularyId",0)),400);
        api.status(client.write("POST","/flashcards",Map.of("vocabularyId",99999999)),404);
        api.status(client.write("POST","/flashcards",Map.of("vocabularyId",1,"userId",UUID.randomUUID())),400);
        api.status(client.write("POST",path(card)+"/reviews",Map.of("requestId",UUID.randomUUID(),"rating","WRONG","version",0)),400);
        api.status(client.write("POST",path(card)+"/reviews",Map.of("rating","AGAIN","version",0)),400);
        api.status(client.write("POST",path(card)+"/reviews",Map.of("requestId",UUID.randomUUID(),"rating","AGAIN","version",-1)),400);
        api.status(client.write("POST",path(card)+"/reviews",Map.of("requestId",UUID.randomUUID(),"rating","AGAIN","version",0,"nextReviewAt","2099-01-01T00:00:00Z")),400);
        for(String q:List.of("page=-1","size=0","size=51","lessonId=0","dueOnly=bad","q="+"a".repeat(101)))api.status(client.get("/flashcards?"+q),400);
        api.status(client.get("/flashcards?lessonId=9999999"),404);
        api.status(client.get("/flashcards/reviews?page=-1"),400);
    }
    @Test void reviewKeepsWordSnapshotAndAdminCannotDeleteReferencedContent()throws Exception{
        var root=api.admin();var client=learner();
        var fields=Map.of("hanzi","旧词","pinyin","jiù cí","meaningVi","Từ cũ","exampleHanzi","这是旧词。","examplePinyin","Zhè shì jiù cí.","exampleMeaningVi","Đây là từ cũ.");
        var created=root.write("POST","/admin/vocabulary",fields);api.status(created,201);long id=api.json(created).get("id").asLong();words.add(id);
        var card=save(client,id);api.status(client.write("POST",path(card)+"/reviews",answer(card,"REMEMBER",UUID.randomUUID())),200);
        var edit=new HashMap<>(fields);edit.put("hanzi","新词");edit.put("meaningVi","Từ mới");
        api.status(root.write("PUT","/admin/vocabulary/"+id,edit),200);
        assertThat(api.json(client.get(path(card))).get("word").get("hanzi").asText()).isEqualTo("新词");
        assertThat(api.json(client.get("/flashcards/reviews")).get("items").get(0).get("hanzi").asText()).isEqualTo("旧词");
        api.status(root.write("DELETE","/admin/vocabulary/"+id,null),409);
    }
}
