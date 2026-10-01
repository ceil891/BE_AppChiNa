package com.chineselearning;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminSkillApiTests {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    AdminUserApiTests api;
    List<String> ids=new ArrayList<>();
    @BeforeEach void setup() { api=new AdminUserApiTests();api.port=port;api.jdbc=jdbc;api.mapper=mapper; }
    @AfterEach void cleanup() { api.cleanup();for(var id:ids)jdbc.sql("DELETE FROM skill_exercises WHERE id=:id").param("id",id).update(); }
    Map<String,Object> input(String skill,long version,String accepted) {
        boolean choice=List.of("LISTENING","READING").contains(skill);
        var q=new HashMap<String,Object>();
        q.put("prompt","Test question");q.put("text","你好");q.put("hint","");q.put("explanation","Test explanation");
        q.put("options",choice?List.of("你好","谢谢"):List.of());
        q.put("accepted",skill.equals("SPEAKING")?List.of():List.of(accepted));
        q.put("audioSrc",List.of("LISTENING","SPEAKING").contains(skill)?"/audio/skills/listening-greetings-v1-1.mp3":"");
        return new HashMap<>(Map.of("title","Editorial test","description","Test content","version",version,"questions",List.of(q)));
    }
    String create(AdminUserApiTests.Client admin,String skill) throws Exception {
        var response=admin.write("POST","/admin/skills/"+skill,input(skill,0,"你好"));api.status(response,201);
        String id=api.json(response).get("exercise").get("id").asText();ids.add(id);return id;
    }
    @Test void allFourSkillsPublishHideAndKeepAttemptSnapshots() throws Exception {
        var admin=api.admin();var learner=api.login(api.create(admin,"USER"),AdminUserApiTests.PASSWORD);
        for(String skill:List.of("LISTENING","SPEAKING","READING","WRITING")) {
            String id=create(admin,skill),base="/admin/skills/"+skill+"/"+id;
            assertThat(learner.get("/skills/exercises").body()).doesNotContain(id);
            api.status(learner.write("POST","/skills/attempts",Map.of("exerciseId",id,"requestId",UUID.randomUUID())),404);
            api.status(admin.write("PATCH",base+"/publication",Map.of("version",0,"published",true)),200);
            assertThat(learner.get("/skills/exercises").body()).contains(id).doesNotContain("accepted");
            var request=Map.of("exerciseId",id,"requestId",UUID.randomUUID());
            var started=learner.write("POST","/skills/attempts",request);api.status(started,200);
            String attempt=api.json(started).get("summary").get("id").asText();
            api.status(admin.write("PUT",base,input(skill,1,"谢谢")),409);
            api.status(admin.write("PATCH",base+"/publication",Map.of("version",1,"published",false)),200);
            var retried=learner.write("POST","/skills/attempts",request);api.status(retried,200);assertThat(api.json(retried)).isEqualTo(api.json(started));
            api.status(admin.write("PUT",base,input(skill,1,"谢谢")),409);
            api.status(admin.write("PUT",base,input(skill,2,"谢谢")),200);
            api.status(admin.write("PATCH",base+"/publication",Map.of("version",2,"published",true)),409);
            var result=learner.write("POST","/skills/attempts/"+attempt+"/submit",Map.of("answers",List.of(Map.of("questionId","1","value",skill.equals("SPEAKING")?"OK":"你好"))));api.status(result,200);
            if(!skill.equals("SPEAKING"))assertThat(api.json(result).get("summary").get("score").asInt()).isEqualTo(100);
            var results=admin.get(base+"/results");api.status(results,200);assertThat(api.json(results).get("total").asInt()).isEqualTo(1);
            assertThat(api.json(results).get("items").get(0).get("id").asText()).isEqualTo(attempt);
            api.status(admin.get(base+"/results?page=-1"),400);
            api.status(admin.write("PATCH",base+"/publication",Map.of("version",3,"published",true)),200);
            var next=learner.write("POST","/skills/attempts",Map.of("exerciseId",id,"requestId",UUID.randomUUID()));api.status(next,200);
            var questions=api.json(next).get("questions");assertThat(questions.get(0).path("accepted").isNull() || questions.get(0).path("accepted").isMissingNode()).isTrue();
        }
    }
    @Test void validationRejectsBrokenAnswersAudioAndCrossSkillUpdates() throws Exception {
        var admin=api.admin();String id=create(admin,"WRITING");
        api.status(admin.get("/admin/skills/READING/"+id),404);
        api.status(admin.write("PUT","/admin/skills/READING/"+id,input("READING",0,"你好")),404);
        api.status(admin.write("POST","/admin/skills/WRITING",input("WRITING",0,"。 ！")),400);
        api.status(admin.write("POST","/admin/skills/READING",input("READING",0,"not an option")),400);
        for(String audio:List.of("", "javascript:alert(1)", "http://example.com/a.mp3", "//example.com/a.mp3")) {
            var body=input("LISTENING",0,"你好");
            @SuppressWarnings("unchecked") var qs=(List<Map<String,Object>>)body.get("questions");qs.get(0).put("audioSrc",audio);
            api.status(admin.write("POST","/admin/skills/LISTENING",body),400);
        }
        var empty=input("WRITING",0,"你好");empty.put("questions",List.of());api.status(admin.write("POST","/admin/skills/WRITING",empty),400);
        var speaking=input("SPEAKING",0,"你好");
        @SuppressWarnings("unchecked") var questions=(List<Map<String,Object>>)speaking.get("questions");questions.get(0).put("accepted",List.of("100"));
        api.status(admin.write("POST","/admin/skills/SPEAKING",speaking),400);
    }
    @Test void editorialAndLearnerResultsPermissionsAreSeparateAndCsrfIsRequired() throws Exception {
        var admin=api.admin();String id=create(admin,"WRITING"),base="/admin/skills/WRITING/"+id;
        var learner=api.login(api.create(admin,"USER"),AdminUserApiTests.PASSWORD);
        api.status(learner.get(base),403);api.status(learner.write("POST","/admin/skills/WRITING",input("WRITING",0,"你好")),403);
        api.status(admin.raw("PUT",base,input("WRITING",0,"你好"),null),403);
        String role="SKILL_EDITOR_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(Locale.ROOT);
        try {
            api.status(admin.write("POST","/admin/roles",Map.of("code",role,"name","Skill reader","description","Test","permissions",List.of("skills.read"))),201);
            var reader=api.login(api.create(admin,role),AdminUserApiTests.PASSWORD);
            api.status(reader.get(base),200);api.status(reader.get(base+"/results"),403);
            api.status(reader.write("POST","/admin/skills/WRITING",input("WRITING",0,"你好")),403);
            api.status(reader.write("PUT",base,input("WRITING",0,"你好")),403);
            api.status(reader.write("PATCH",base+"/publication",Map.of("version",0,"published",true)),403);
            jdbc.sql("DELETE FROM role_permissions WHERE role_code=:role").param("role",role).update();
            api.status(reader.get(base),403);
        } finally { api.cleanup();jdbc.sql("DELETE FROM roles WHERE code=:role").param("role",role).update(); }
    }
}
