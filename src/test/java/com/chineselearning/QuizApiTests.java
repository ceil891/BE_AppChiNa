package com.chineselearning;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class QuizApiTests {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    AdminUserApiTests api;
    final List<Long> temporarySets=new ArrayList<>();
    @BeforeEach void setup(){api=new AdminUserApiTests();api.port=port;api.jdbc=jdbc;api.mapper=mapper;}
    @AfterEach void cleanup(){api.cleanup();for(long id:temporarySets)jdbc.sql("DELETE FROM quiz_sets WHERE id=:id").param("id",id).update();}
    AdminUserApiTests.Client learner()throws Exception{
        var client=api.new Client();String email=api.email();
        api.status(client.write("POST","/auth/register",Map.of("email",email,"password",AdminUserApiTests.PASSWORD,"displayName","Quiz test")),201);
        api.status(client.write("POST","/auth/login",Map.of("email",email,"password",AdminUserApiTests.PASSWORD)),200);return client;
    }
    Map<String,Object> startInput(long set,int count,UUID key){return Map.of("setId",set,"questionCount",count,"requestId",key);}
    JsonNode start(AdminUserApiTests.Client c,int count)throws Exception{return start(c,1,count,UUID.randomUUID());}
    JsonNode start(AdminUserApiTests.Client c,long set,int count,UUID key)throws Exception{
        var response=c.write("POST","/quizzes/attempts",startInput(set,count,key));api.status(response,200);return api.json(response);
    }
    String path(JsonNode data){return "/quizzes/attempts/"+data.get("attempt").get("id").asText();}
    long version(JsonNode data){return data.get("attempt").get("version").asLong();}
    UUID correct(JsonNode question){return jdbc.sql("SELECT correct_option_id FROM quiz_attempt_questions WHERE id=:id")
        .param("id",UUID.fromString(question.get("id").asText())).query(UUID.class).single();}
    UUID wrong(JsonNode question){var correct=correct(question);for(var choice:question.get("options")) {
        var id=UUID.fromString(choice.get("id").asText());if(!id.equals(correct))return id;
    }throw new AssertionError("Missing distractor");}
    Map<String,Object> answerInput(JsonNode data,JsonNode q,UUID option,UUID key){return Map.of("requestId",key,"questionId",q.get("id").asText(),"optionId",option,"version",version(data));}
    JsonNode answer(AdminUserApiTests.Client c,JsonNode data,JsonNode q,UUID option)throws Exception{
        var response=c.write("PUT",path(data)+"/answers",answerInput(data,q,option,UUID.randomUUID()));api.status(response,200);return api.json(response);
    }
    JsonNode fill(AdminUserApiTests.Client c,JsonNode data,int right)throws Exception{
        var current=data;int i=0;for(var q:data.get("questions"))current=answer(c,current,q,i++<right?correct(q):wrong(q));return current;
    }
    JsonNode submit(AdminUserApiTests.Client c,JsonNode data)throws Exception{
        var response=c.write("POST",path(data)+"/submit",Map.of("version",version(data)));api.status(response,200);return api.json(response);
    }
    void assertHidden(String json){assertThat(json).doesNotContain("correctOptionId","explanation","correctIndex","correct_index","correct_option_id","\"correct\":");}

    Map<String,Object> editorialInput(long version,int count) {
        var questions=new ArrayList<Map<String,Object>>();
        for(int i=0;i<count;i++)questions.add(Map.of("type","CHOOSE_MEANING","prompt","Câu "+i,
            "options",List.of("Một","Hai","Ba","Bốn"),"correctIndex",0,"explanation","Đáp án một."));
        return Map.of("title","Editorial test","description","Bộ đề kiểm thử","version",version,"questions",questions);
    }
    @Test void editorialLifecycleProtectsDraftsVersionsAndExistingAttempts() throws Exception {
        var admin=api.admin();var c=learner();
        var created=admin.write("POST","/admin/quizzes",editorialInput(0,4));api.status(created,201);
        long id=api.json(created).get("id").asLong();temporarySets.add(id);String path="/admin/quizzes/"+id;
        api.status(c.write("POST","/quizzes/attempts",startInput(id,5,UUID.randomUUID())),404);
        api.status(admin.write("PATCH",path+"/publication",Map.of("version",0,"published",true)),409);
        api.status(admin.write("PUT",path,editorialInput(0,5)),200);
        api.status(admin.write("PUT",path,editorialInput(0,5)),409);
        api.status(admin.write("PATCH",path+"/publication",Map.of("version",1,"published",true)),200);
        var attempt=start(c,id,5,UUID.randomUUID());
        api.status(admin.write("PUT",path,editorialInput(2,5)),409);
        api.status(admin.write("DELETE",path+"?version=2",null),409);
        api.status(admin.write("PATCH",path+"/publication",Map.of("version",2,"published",false)),200);
        api.status(admin.write("DELETE",path+"?version=2",null),409);
        api.status(admin.write("DELETE",path+"?version=3",null),204);
        assertThat(api.json(c.get(path(attempt)))).isEqualTo(attempt);
        assertThat(submit(c,fill(c,attempt,5)).get("attempt").get("score").asInt()).isEqualTo(100);
    }
    @Test void editorialApiRejectsUnauthorizedAccessAndAmbiguousChoices() throws Exception {
        var admin=api.admin();var c=learner();
        api.status(c.get("/admin/quizzes"),403);
        api.status(c.get("/admin/quizzes/1"),403);
        api.status(c.write("POST","/admin/quizzes",editorialInput(0,5)),403);
        api.status(c.write("PUT","/admin/quizzes/1",editorialInput(0,5)),403);
        api.status(c.write("PATCH","/admin/quizzes/1/publication",Map.of("version",0,"published",false)),403);
        api.status(c.write("DELETE","/admin/quizzes/1?version=0",null),403);
        var bad=new HashMap<>(editorialInput(0,0));
        bad.put("questions",List.of(Map.of("type","CHOOSE_WORD","prompt","Test","options",List.of("a"," a ","b","c"),"correctIndex",0,"explanation","Test")));
        api.status(admin.write("POST","/admin/quizzes",bad),400);
        bad.put("questions",List.of(Map.of("type","OTHER","prompt","Test","options",List.of("a","b","c","d"),"correctIndex",4,"explanation","Test")));
        api.status(admin.write("POST","/admin/quizzes",bad),400);
    }

    @Test void editorialBankAndAttemptsNeverExposeAnswersBeforeSubmission()throws Exception{
        var c=learner();var sets=c.get("/quizzes/sets");api.status(sets,200);assertHidden(sets.body());
        assertThat(api.json(sets).size()).isGreaterThanOrEqualTo(12);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM quiz_questions").query(Integer.class).single()).isGreaterThanOrEqualTo(70);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM quiz_sets s WHERE s.published AND (SELECT COUNT(*) FROM quiz_questions q WHERE q.set_id=s.id)<5").query(Integer.class).single()).isZero();
        for(int count:List.of(5,10)){
            var data=start(c,count);assertThat(data.get("questions").size()).isEqualTo(count);
            assertThat(data.get("attempt").get("score").isNull()).isTrue();assertHidden(mapper.writeValueAsString(data));
            var ids=new HashSet<String>();var prompts=new HashSet<String>();
            for(var q:data.get("questions")){
                assertThat(q.get("options").size()).isEqualTo(4);assertThat(prompts.add(q.get("prompt").asText())).isTrue();
                for(var choice:q.get("options"))assertThat(ids.add(choice.get("id").asText())).isTrue();
            }
            assertHidden(c.get(path(data)).body());assertHidden(c.get("/quizzes/attempts").body());
        }
    }
    @Test void answerPersistsCanBeChangedAndRetryCannotUndoANewerAnswer()throws Exception{
        var c=learner();var data=start(c,5);var q=data.get("questions").get(0);
        var input=answerInput(data,q,correct(q),UUID.randomUUID());
        var saved=c.write("PUT",path(data)+"/answers",input);api.status(saved,200);assertHidden(saved.body());
        var after=api.json(saved);assertThat(version(after)).isEqualTo(1);
        assertThat(after.get("attempt").get("answeredCount").asInt()).isEqualTo(1);
        var reload=api.new Client();reload.cookie=c.cookie;
        assertThat(api.json(reload.get(path(data)))).isEqualTo(after);
        assertThat(api.json(reload.write("PUT",path(data)+"/answers",input))).isEqualTo(after);
        var changed=answer(c,after,q,wrong(q));assertThat(version(changed)).isEqualTo(2);
        var replay=api.json(c.write("PUT",path(data)+"/answers",input));assertThat(replay).isEqualTo(changed);
        assertThat(replay.get("questions").get(0).get("selectedOptionId").asText()).isEqualTo(wrong(q).toString());
        api.status(c.write("PUT",path(data)+"/answers",answerInput(data,q,correct(q),UUID.randomUUID())),409);
        var reused=new HashMap<>(input);reused.put("optionId",wrong(q));api.status(c.write("PUT",path(data)+"/answers",reused),409);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM quiz_answer_requests WHERE attempt_id=:id").param("id",UUID.fromString(data.get("attempt").get("id").asText())).query(Integer.class).single()).isEqualTo(2);
    }
    @Test void serverScoresWrongMixedAndPerfectAnswersAndSubmissionIsFinal()throws Exception{
        var c=learner();
        for(int right:List.of(0,2,5)){
            var draft=start(c,5);api.status(c.write("POST",path(draft)+"/submit",Map.of("version",0)),409);
            draft=fill(c,draft,right);var result=submit(c,draft);var summary=result.get("attempt");
            assertThat(summary.get("status").asText()).isEqualTo("SUBMITTED");assertThat(summary.get("score").asInt()).isEqualTo(right*20);
            assertThat(summary.get("correctCount").asInt()).isEqualTo(right);assertThat(summary.get("submittedAt").isNull()).isFalse();
            long correct=0;for(var q:result.get("questions")){if(q.get("correct").asBoolean())correct++;assertThat(q.get("explanation").asText()).isNotBlank();}
            assertThat(correct).isEqualTo(right);assertThat(submit(c,draft)).isEqualTo(result);assertThat(api.json(c.get(path(draft)))).isEqualTo(result);
            var question=draft.get("questions").get(0);
            api.status(c.write("PUT",path(draft)+"/answers",answerInput(result,question,wrong(question),UUID.randomUUID())),409);
        }
        assertThat(api.json(c.get("/quizzes/attempts?status=SUBMITTED")).get("total").asInt()).isEqualTo(3);
        assertThat(api.json(c.get("/quizzes/attempts?status=IN_PROGRESS")).get("total").asInt()).isZero();
    }
    long copiedSet(){
        long id=jdbc.sql("INSERT INTO quiz_sets(title,description,published) VALUES ('Snapshot fixture','Temporary bank',true) RETURNING id").query(Long.class).single();temporarySets.add(id);
        jdbc.sql("INSERT INTO quiz_questions(set_id,position,type,prompt,options,correct_index,explanation) SELECT :id,position,type,prompt,options,correct_index,explanation FROM quiz_questions WHERE set_id=1")
            .param("id",id).update();return id;
    }
    @Test void snapshotsAndStartReplaySurviveEditingAndDeletingSourceBank()throws Exception{
        var c=learner();long set=copiedSet();var key=UUID.randomUUID();var initial=start(c,set,10,key);
        jdbc.sql("UPDATE quiz_questions SET prompt='Edited',explanation='Edited explanation',correct_index=(correct_index+1)%4 WHERE set_id=:id").param("id",set).update();
        jdbc.sql("UPDATE quiz_sets SET title='Edited title' WHERE id=:id").param("id",set).update();
        assertThat(api.json(c.get(path(initial)))).isEqualTo(initial);
        jdbc.sql("DELETE FROM quiz_sets WHERE id=:id").param("id",set).update();
        assertThat(start(c,set,10,key)).isEqualTo(initial);
        var result=submit(c,fill(c,initial,10));assertThat(result.get("attempt").get("score").asInt()).isEqualTo(100);
        assertThat(mapper.writeValueAsString(result)).doesNotContain("Edited");
        assertThat(result.get("attempt").get("title").asText()).isEqualTo("Snapshot fixture");
    }
    @Test void ownershipLogoutAndAccountDeletionProtectQuizData()throws Exception{
        var admin=api.admin();var accountA=api.create(admin,"USER");var accountB=api.create(admin,"USER");
        var a=api.login(accountA,AdminUserApiTests.PASSWORD);var b=api.login(accountB,AdminUserApiTests.PASSWORD);
        var data=start(a,5);var q=data.get("questions").get(0);var payload=answerInput(data,q,correct(q),UUID.randomUUID());
        api.status(b.get(path(data)),404);api.status(b.write("PUT",path(data)+"/answers",payload),404);
        api.status(b.write("POST",path(data)+"/submit",Map.of("version",0)),404);
        assertThat(b.get("/quizzes/attempts").body()).doesNotContain(data.get("attempt").get("id").asText());
        var ownB=start(b,5);var ownA=answer(a,data,q,correct(q));
        api.status(a.write("POST","/auth/logout",null),204);api.status(a.get(path(data)),401);
        a=api.login(accountA,AdminUserApiTests.PASSWORD);assertThat(api.json(a.get(path(data)))).isEqualTo(ownA);
        api.status(admin.write("DELETE","/admin/users/"+accountA.get("id").asText(),null),204);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM quiz_attempt_questions WHERE attempt_id=:id").param("id",UUID.fromString(data.get("attempt").get("id").asText())).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM quiz_answer_requests WHERE attempt_id=:id").param("id",UUID.fromString(data.get("attempt").get("id").asText())).query(Integer.class).single()).isZero();
        assertThat(api.json(b.get(path(ownB)))).isEqualTo(ownB);api.status(a.get(path(data)),401);
    }
    List<HttpResponse<String>> parallel(AdminUserApiTests.Client client,String methodA,String pathA,Object bodyA,String methodB,String pathB,Object bodyB)throws Exception{
        var a=api.new Client();a.cookie=client.cookie;var b=api.new Client();b.cookie=client.cookie;String csrf=client.csrf();
        var latch=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var first=pool.submit(()->{latch.await();return a.raw(methodA,pathA,bodyA,csrf);});
            var second=pool.submit(()->{latch.await();return b.raw(methodB,pathB,bodyB,csrf);});latch.countDown();
            return List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
    }
    @Test void concurrentStartAndAnswerRetriesHaveOneEffect()throws Exception{
        var c=learner();var input=startInput(1,5,UUID.randomUUID());
        var started=parallel(c,"POST","/quizzes/attempts",input,"POST","/quizzes/attempts",input);
        for(var r:started)api.status(r,200);assertThat(api.json(started.get(0))).isEqualTo(api.json(started.get(1)));
        assertThat(api.json(c.get("/quizzes/attempts")).get("total").asInt()).isEqualTo(1);
        var different=new HashMap<>(input);different.put("questionCount",10);api.status(c.write("POST","/quizzes/attempts",different),409);
        var data=api.json(started.get(0));var q=data.get("questions").get(0);var answer=answerInput(data,q,correct(q),UUID.randomUUID());
        var answered=parallel(c,"PUT",path(data)+"/answers",answer,"PUT",path(data)+"/answers",answer);
        for(var r:answered)api.status(r,200);assertThat(api.json(answered.get(0))).isEqualTo(api.json(answered.get(1)));
        assertThat(version(api.json(c.get(path(data))))).isEqualTo(1);
        data=api.json(answered.get(0));
        var races=parallel(c,"PUT",path(data)+"/answers",answerInput(data,q,correct(q),UUID.randomUUID()),"PUT",path(data)+"/answers",answerInput(data,q,wrong(q),UUID.randomUUID()));
        assertThat(races.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200,409);
        assertThat(version(api.json(c.get(path(data))))).isEqualTo(2);
    }
    @Test void concurrentSubmissionsAndAnswerSubmitRaceKeepOneConsistentResult()throws Exception{
        var c=learner();var data=fill(c,start(c,5),5);var payload=Map.of("version",version(data));
        var results=parallel(c,"POST",path(data)+"/submit",payload,"POST",path(data)+"/submit",payload);
        for(var r:results)api.status(r,200);assertThat(api.json(results.get(0))).isEqualTo(api.json(results.get(1)));
        assertThat(version(api.json(results.get(0)))).isEqualTo(version(data)+1);
        data=fill(c,start(c,5),5);var q=data.get("questions").get(0);
        results=parallel(c,"PUT",path(data)+"/answers",answerInput(data,q,wrong(q),UUID.randomUUID()),"POST",path(data)+"/submit",Map.of("version",version(data)));
        assertThat(results.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200,409);
        var latest=api.json(c.get(path(data)));var result=submit(c,latest);
        int expected=result.get("questions").get(0).get("correct").asBoolean()?100:80;
        assertThat(result.get("attempt").get("score").asInt()).isEqualTo(expected);
        assertThat(submit(c,result)).isEqualTo(result);
    }
    @Test void validationCsrfAndForeignChoicesCannotManipulateScores()throws Exception{
        var anonymous=api.new Client();api.status(anonymous.get("/quizzes/sets"),401);api.status(anonymous.get("/quizzes/attempts"),401);
        var c=learner();api.status(c.raw("POST","/quizzes/attempts",startInput(1,5,UUID.randomUUID()),null),403);
        api.status(c.write("POST","/quizzes/attempts",startInput(1,6,UUID.randomUUID())),400);
        api.status(c.write("POST","/quizzes/attempts",startInput(-1,5,UUID.randomUUID())),400);
        api.status(c.write("POST","/quizzes/attempts",startInput(999999,5,UUID.randomUUID())),404);
        api.status(c.write("POST","/quizzes/attempts",Map.of()),400);
        var injected=new HashMap<>(startInput(1,5,UUID.randomUUID()));injected.put("userId",UUID.randomUUID());api.status(c.write("POST","/quizzes/attempts",injected),400);
        var data=start(c,5);var q=data.get("questions").get(0);var other=data.get("questions").get(1);
        api.status(c.write("PUT",path(data)+"/answers",answerInput(data,q,correct(other),UUID.randomUUID())),400);
        var otherAttempt=start(c,5);var foreign=otherAttempt.get("questions").get(0);
        api.status(c.write("PUT",path(data)+"/answers",answerInput(data,foreign,correct(foreign),UUID.randomUUID())),400);
        var invalid=new HashMap<>(answerInput(data,q,correct(q),UUID.randomUUID()));invalid.put("version",-1);api.status(c.write("PUT",path(data)+"/answers",invalid),400);
        api.status(c.raw("PUT",path(data)+"/answers",answerInput(data,q,correct(q),UUID.randomUUID()),null),403);
        api.status(c.write("POST",path(data)+"/submit",Map.of("version",0,"score",100)),400);
        api.status(c.raw("POST",path(data)+"/submit",Map.of("version",0),null),403);
        api.status(c.write("POST",path(data)+"/submit",Map.of("version",0)),409);
        for(var filter:List.of("status=BAD","page=-1","size=0","size=51"))api.status(c.get("/quizzes/attempts?"+filter),400);
        api.status(c.get("/quizzes/attempts/not-uuid"),400);api.status(c.get("/quizzes/attempts/"+UUID.randomUUID()),404);
        assertThat(api.json(c.get(path(data)))).isEqualTo(data);
    }
    @Test void insufficientOrAmbiguousBanksCreateNoPartialAttempts()throws Exception{
        var c=learner();long set=copiedSet();
        jdbc.sql("DELETE FROM quiz_questions WHERE set_id=:id AND position>5").param("id",set).update();
        api.status(c.write("POST","/quizzes/attempts",startInput(set,10,UUID.randomUUID())),409);
        jdbc.sql("UPDATE quiz_questions SET options='[\"same\",\"same\",\"third\",\"fourth\"]'::jsonb WHERE set_id=:id").param("id",set).update();
        api.status(c.write("POST","/quizzes/attempts",startInput(set,5,UUID.randomUUID())),409);
        assertThat(api.json(c.get("/quizzes/attempts")).get("total").asInt()).isZero();
    }
    @Test void historyPaginationAndFiltersStayScopedToOwner()throws Exception{
        var a=learner();var b=learner();start(a,5);submit(a,fill(a,start(a,5),3));start(b,5);
        var page=api.json(a.get("/quizzes/attempts?size=1"));assertThat(page.get("total").asInt()).isEqualTo(2);assertThat(page.get("items").size()).isEqualTo(1);
        var next=api.json(a.get("/quizzes/attempts?size=1&page=1"));assertThat(next.get("items").get(0).get("id")).isNotEqualTo(page.get("items").get(0).get("id"));
        assertThat(api.json(a.get("/quizzes/attempts?page=20")).get("items").isEmpty()).isTrue();
        assertThat(api.json(a.get("/quizzes/attempts?status=IN_PROGRESS")).get("total").asInt()).isEqualTo(1);
        assertThat(api.json(a.get("/quizzes/attempts?status=SUBMITTED")).get("total").asInt()).isEqualTo(1);
        assertThat(api.json(b.get("/quizzes/attempts")).get("total").asInt()).isEqualTo(1);
    }
}
