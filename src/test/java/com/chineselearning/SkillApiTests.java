package com.chineselearning;

import com.chineselearning.modules.skills.SkillService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SkillApiTests {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    AdminUserApiTests api;
    @BeforeEach void setup() { api=new AdminUserApiTests();api.port=port;api.jdbc=jdbc;api.mapper=mapper; }
    @AfterEach void cleanup() { api.cleanup(); }
    AdminUserApiTests.Client learner() throws Exception {
        var c=api.new Client();String email=api.email();
        api.status(c.write("POST","/auth/register",Map.of("email",email,"password",AdminUserApiTests.PASSWORD,"displayName","Skills test")),201);
        api.status(c.write("POST","/auth/login",Map.of("email",email,"password",AdminUserApiTests.PASSWORD)),200);return c;
    }
    JsonNode start(AdminUserApiTests.Client c,String exercise) throws Exception {
        var response=c.write("POST","/skills/attempts",Map.of("exerciseId",exercise,"requestId",UUID.randomUUID()));api.status(response,200);return api.json(response);
    }
    String path(JsonNode attempt) { return "/skills/attempts/"+attempt.get("summary").get("id").asText(); }
    Map<String,Object> answers(String... values) {
        var list=new ArrayList<Map<String,String>>();for(int i=0;i<values.length;i++)list.add(Map.of("questionId",String.valueOf(i+1),"value",values[i]));
        return Map.of("answers",list);
    }
    JsonNode submit(AdminUserApiTests.Client c,JsonNode attempt,String... values) throws Exception {
        var r=c.write("POST",path(attempt)+"/submit",answers(values));api.status(r,200);return api.json(r);
    }
    @Test void allFourSkillsHaveContentAndNewLearnersHaveNoInventedScores() throws Exception {
        var anon=api.new Client();api.status(anon.get("/skills/exercises"),401);api.status(anon.get("/skills/stats"),401);
        var c=learner();var catalogue=api.json(c.get("/skills/exercises"));assertThat(catalogue.size()).isGreaterThanOrEqualTo(10);
        assertThat(catalogue.toString()).contains("listening-dialogue-intro-v1","reading-family-v1");
        assertThat(catalogue.toString()).doesNotContain("accepted","explanation");
        var stats=api.json(c.get("/skills/stats"));assertThat(stats.size()).isEqualTo(4);
        for(var s:stats) { assertThat(s.get("attempts").asLong()).isZero();assertThat(s.path("averageScore").isMissingNode()||s.path("averageScore").isNull()).isTrue(); }
        api.status(c.get("/skills/attempts?skill=BAD"),400);api.status(c.get("/skills/attempts?page=-1"),400);
        api.status(c.get("/skills/attempts?size=999"),400);
    }
    @Test void draftsPersistPrivatelyWithoutScoringAndRejectStaleWrites() throws Exception {
        var a=learner();var b=learner();var attempt=start(a,"writing-words-v1");
        var url=path(attempt)+"/draft";
        var body=Map.of("version",0,"answers",List.of(Map.of("questionId","1","value","你好")));
        api.status(a.raw("PUT",url,body,null),403);
        api.status(b.write("PUT",url,body),404);
        var saved=a.write("PUT",url,body);api.status(saved,200);
        assertThat(api.json(saved).get("draftVersion").asLong()).isEqualTo(1);
        assertThat(api.json(a.write("PUT",url,body))).isEqualTo(api.json(saved));
        var restored=api.json(a.get(path(attempt)));
        assertThat(restored.get("questions").get(0).get("answer").asText()).isEqualTo("你好");
        assertThat(restored.get("summary").path("submittedAt").isNull()||restored.get("summary").path("submittedAt").isMissingNode()).isTrue();
        assertThat(restored.toString()).doesNotContain("\"accepted\":[","\"correct\":true");
        assertThat(api.json(a.get("/progress/events?kind=SKILL_PRACTICED")).get("total").asInt()).isZero();
        api.status(a.write("PUT",url,Map.of("version",0,"answers",List.of())),409);
        api.status(a.write("PUT",url,Map.of("version",1,"answers",List.of(Map.of("questionId","other","value","你好")))),400);
        api.status(a.write("PUT",url,Map.of("version",1,"answers",List.of(Map.of("questionId","1","value","你"),Map.of("questionId","1","value","好")))),400);
        api.status(a.write("PUT",url,Map.of("version",1,"answers",List.of())),200);
        assertThat(api.json(a.get(path(attempt))).get("draftVersion").asLong()).isEqualTo(2);
        submit(a,attempt,"你好","谢谢","学校");
        api.status(a.write("PUT",url,Map.of("version",2,"answers",List.of())),409);
        assertThat(api.json(a.get(path(attempt))).get("summary").get("score").asInt()).isEqualTo(100);
    }
    @Test void draftChoicesAndSpeakingRatingsAreValidated() throws Exception {
        var a=learner();
        for(String exercise:List.of("reading-friend-v1","listening-greetings-v1","speaking-introduction-v1")) {
            var attempt=start(a,exercise);var q=attempt.get("questions").get(0);
            String answer=exercise.startsWith("speaking")?"OK":q.get("options").get(0).asText();
            api.status(a.write("PUT",path(attempt)+"/draft",Map.of("version",0,"answers",List.of(Map.of("questionId",q.get("id").asText(),"value","invalid")))),400);
            api.status(a.write("PUT",path(attempt)+"/draft",Map.of("version",0,"answers",List.of(Map.of("questionId",q.get("id").asText(),"value",answer)))),200);
            assertThat(api.json(a.get(path(attempt))).get("questions").get(0).get("answer").asText()).isEqualTo(answer);
        }
    }
    @Test void simultaneousDraftEditsDoNotOverwriteEachOther() throws Exception {
        var a=learner();var attempt=start(a,"writing-words-v1");
        var b=api.new Client();b.cookie=a.cookie;
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=Map.of("version",0,"answers",List.of(Map.of("questionId","1","value","你")));
            var second=Map.of("version",0,"answers",List.of(Map.of("questionId","1","value","你好")));
            var one=pool.submit(()->a.write("PUT",path(attempt)+"/draft",first));
            var two=pool.submit(()->b.write("PUT",path(attempt)+"/draft",second));
            assertThat(List.of(one.get(20,TimeUnit.SECONDS).statusCode(),two.get(20,TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(200,409);
            assertThat(api.json(a.get(path(attempt))).get("draftVersion").asLong()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void writingIsScoredOnServerAndDuplicateSubmissionCountsOneStudyActivity() throws Exception {
        var c=learner();var draft=start(c,"writing-words-v1");
        assertThat(draft.toString()).doesNotContain("\"accepted\":[","你好","谢谢");
        var result=submit(c,draft,"你 好！","谢谢。","学校");
        assertThat(result.get("summary").get("score").asInt()).isEqualTo(100);
        assertThat(result.get("questions").get(0).get("correct").asBoolean()).isTrue();
        assertThat(submit(c,draft,"你 好！","谢谢。","学校")).isEqualTo(result);
        api.status(c.write("POST",path(draft)+"/submit",answers("你好","wrong","学校")),409);
        var history=api.json(c.get("/skills/attempts?skill=WRITING"));assertThat(history.get("total").asInt()).isEqualTo(1);
        var stats=api.json(c.get("/skills/stats"));
        for(var s:stats)if(s.get("skill").asText().equals("WRITING")) { assertThat(s.get("averageScore").asInt()).isEqualTo(100);assertThat(s.get("completedExercises").asInt()).isEqualTo(1); }
        var progress=api.json(c.get("/progress/events?kind=SKILL_PRACTICED"));assertThat(progress.get("total").asInt()).isEqualTo(1);
        assertThat(api.json(c.get("/progress")).get("streak").get("studiedToday").asBoolean()).isTrue();
        assertThat(api.json(c.get(path(draft)))).isEqualTo(result);
    }
    @Test void listeningReadingAndSpeakingHaveDifferentAssessmentModes() throws Exception {
        var c=learner();var listening=start(c,"listening-greetings-v1");
        var heard=submit(c,listening,"Xin chào!","Cảm ơn bạn","Hẹn gặp ngày mai!");
        assertThat(heard.get("summary").get("score").asInt()).isEqualTo(100);
        var reading=start(c,"reading-friend-v1");
        var read=submit(c,reading,"Tiểu Minh","Bắc Kinh","Nước");assertThat(read.get("summary").get("score").asInt()).isEqualTo(33);
        var speaking=start(c,"speaking-introduction-v1");
        api.status(c.write("POST",path(speaking)+"/submit",answers("100","OK","OK")),400);
        var spoken=submit(c,speaking,"AGAIN","OK","CONFIDENT");
        assertThat(spoken.get("summary").path("score").isNull()||spoken.get("summary").path("score").isMissingNode()).isTrue();
        assertThat(spoken.get("questions").get(0).get("answer").asText()).isEqualTo("AGAIN");
        assertThat(api.json(c.get("/skills/attempts?size=2")).get("items").size()).isEqualTo(2);
        assertThat(api.json(c.get("/skills/attempts?size=2&page=1")).get("items").size()).isEqualTo(1);
    }
    @Test void ownershipAndCsrfPreventReadingOrChangingAnotherLearnersResults() throws Exception {
        var a=learner();var b=learner();var attempt=start(a,"writing-words-v1");
        api.status(b.get(path(attempt)),404);api.status(b.write("POST",path(attempt)+"/submit",answers("你好","谢谢","学校")),404);
        assertThat(api.json(b.get("/skills/attempts")).get("total").asInt()).isZero();
        api.status(a.raw("POST",path(attempt)+"/submit",answers("你好","谢谢","学校"),null),403);
        var malicious=new HashMap<>(answers("你好","谢谢","学校"));malicious.put("score",100);
        api.status(a.write("POST",path(attempt)+"/submit",malicious),400);
        assertThat(api.json(a.get("/progress/events?kind=SKILL_PRACTICED")).get("total").asInt()).isZero();
        submit(a,attempt,"你好","谢谢","学校");
        for(var s:api.json(b.get("/skills/stats")))assertThat(s.get("attempts").asInt()).isZero();
    }
    @Test void invalidAnswersDoNotFinalizeAndStartRetryDoesNotCreateDuplicates() throws Exception {
        var c=learner();var key=UUID.randomUUID();var input=Map.of("exerciseId","reading-friend-v1","requestId",key);
        var r=c.write("POST","/skills/attempts",input);api.status(r,200);var attempt=api.json(r);
        assertThat(api.json(c.write("POST","/skills/attempts",input))).isEqualTo(attempt);
        api.status(c.write("POST","/skills/attempts",Map.of("exerciseId","writing-words-v1","requestId",key)),409);
        api.status(c.write("POST",path(attempt)+"/submit",answers("Tiểu Minh")),400);
        api.status(c.write("POST",path(attempt)+"/submit",answers("Invalid","Hà Nội","Trà")),400);
        api.status(c.write("POST",path(attempt)+"/submit",Map.of("answers",List.of(Map.of("questionId","1","value","Tiểu Minh"),Map.of("questionId","1","value","Tiểu Minh"),Map.of("questionId","3","value","Trà")))),400);
        assertThat(api.json(c.get("/skills/attempts")).get("total").asInt()).isEqualTo(1);
        assertThat(submit(c,attempt,"Tiểu Minh","Hà Nội","Trà").get("summary").get("score").asInt()).isEqualTo(100);
    }
    @Test void concurrentSubmissionsOnlyRecordOneResultAndEvent() throws Exception {
        var c=learner();var attempt=start(c,"writing-sentences-v1");
        var pool=Executors.newFixedThreadPool(2);
        try {
            var body=answers("我学习中文","我喜欢喝茶","他是学生");
            // Separate HTTP clients share only the test session cookie.
            var other=api.new Client();other.cookie=c.cookie;
            var one=pool.submit(()->c.write("POST",path(attempt)+"/submit",body));
            var two=pool.submit(()->other.write("POST",path(attempt)+"/submit",body));
            api.status(one.get(20,TimeUnit.SECONDS),200);api.status(two.get(20,TimeUnit.SECONDS),200);
            assertThat(api.json(c.get("/progress/events?kind=SKILL_PRACTICED")).get("total").asInt()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void writingNormalizationDoesNotAcceptWrongCharacters() {
        assertThat(SkillService.normalize(" 我 学习 中文！ ")).isEqualTo("我学习中文");
        assertThat(SkillService.normalize("我学习中文")).isNotEqualTo(SkillService.normalize("我学中文"));
    }
    @Test void adminCanInspectTheSelectedLearnersSkillsWithoutChangingResults() throws Exception {
        var root=api.admin();var a=learner();var b=learner();
        String owner=api.json(a.get("/auth/me")).get("id").asText();
        String other=api.json(b.get("/auth/me")).get("id").asText();
        var draft=start(a,"writing-words-v1");var result=submit(a,draft,"你好","谢谢","学校");
        String base="/admin/users/"+owner+"/skills";
        api.status(root.get(base),200);
        var stats=api.json(root.get(base));
        assertThat(stats.size()).isEqualTo(4);
        for(var s:stats)if(s.get("skill").asText().equals("WRITING"))assertThat(s.get("bestScore").asInt()).isEqualTo(100);
        assertThat(api.json(root.get(base+"/attempts?skill=WRITING")).get("total").asInt()).isEqualTo(1);
        String attemptId=result.get("summary").get("id").asText();
        assertThat(api.json(root.get(base+"/attempts/"+attemptId))).isEqualTo(result);
        api.status(root.get("/admin/users/"+other+"/skills/attempts/"+attemptId),404);
        api.status(root.get("/admin/users/"+UUID.randomUUID()+"/skills"),404);
        api.status(root.write("POST",base+"/attempts/"+attemptId,answers("wrong","wrong","wrong")),403);
        api.status(a.get(base),403);api.status(b.get(base+"/attempts"),403);
        assertThat(api.json(a.get(path(draft)))).isEqualTo(result);
    }
    @Test void skillOversightRequiresExplicitPermissionAndRevocationIsImmediate() throws Exception {
        var root=api.admin();String code="TEST_SKILL_"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(Locale.ROOT);
        try {
            api.status(root.write("POST","/admin/roles",Map.of("code",code,"name","Skill observer","description","Test only","permissions",List.of("users.read"))),201);
            var reader=api.login(api.create(root,code),AdminUserApiTests.PASSWORD);
            var target=api.create(root,"USER");String base="/admin/users/"+target.get("id").asText()+"/skills";
            api.status(reader.get(base),403);api.status(reader.get(base+"/attempts"),403);
            jdbc.sql("INSERT INTO role_permissions(role_code,permission_code) VALUES(:role,'users.skills.read')").param("role",code).update();
            api.status(reader.get(base),200);api.status(reader.get(base+"/attempts"),200);
            jdbc.sql("DELETE FROM role_permissions WHERE role_code=:role AND permission_code='users.read'").param("role",code).update();
            api.status(reader.get(base),403);api.status(reader.get(base+"/attempts"),403);
        } finally { api.cleanup();jdbc.sql("DELETE FROM roles WHERE code=:role").param("role",code).update(); }
    }
}
