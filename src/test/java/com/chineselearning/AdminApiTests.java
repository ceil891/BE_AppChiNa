package com.chineselearning;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminApiTests {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    final List<String> emails=new ArrayList<>();
    final List<Long> wordIds=new ArrayList<>(),lessonIds=new ArrayList<>();
    static final String PASSWORD="Admin-test-only-password!";

    @Test void coursesRespectDraftsOrderVersionsAndPublication() throws Exception {
        var client=admin();var guest=new Client();
        var student=new Client();String studentEmail=register(student);
        assertThat(student.write("POST","/auth/login",Map.of("email",studentEmail,"password",PASSWORD)).statusCode()).isEqualTo(200);
        long a=json(client.write("POST","/admin/lessons",lesson("Course A",99101,List.of()))).get("lesson").get("id").asLong();lessonIds.add(a);
        long b=json(client.write("POST","/admin/lessons",lesson("Course B",99102,List.of()))).get("lesson").get("id").asLong();lessonIds.add(b);
        var body=Map.of("title","Khóa kiểm thử","description","Lộ trình","lessonIds",List.of(b,a),"version",0);
        var created=client.write("POST","/admin/courses",body);
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);
        long id=json(created).get("course").get("id").asLong();
        try {
            assertThat(json(created).get("lessons").get(0).get("id").asLong()).isEqualTo(b);
            assertThat(guest.get("/courses/"+id).statusCode()).isEqualTo(403);
            assertThat(student.get("/courses/"+id).statusCode()).isEqualTo(404);
            assertThat(guest.get("/admin/courses").statusCode()).isEqualTo(401);
            assertThat(client.raw("PUT","/admin/courses/"+id,body,null).statusCode()).isEqualTo(403);
            var invalid=Map.of("title","Invalid","description","","lessonIds",List.of(a,a),"version",0);
            assertThat(client.write("PUT","/admin/courses/"+id,invalid).statusCode()).isEqualTo(400);
            assertThat(client.write("PUT","/admin/courses/"+id,body).statusCode()).isEqualTo(200);
            assertThat(client.write("PUT","/admin/courses/"+id,body).statusCode()).isEqualTo(409);
            assertThat(client.write("PATCH","/admin/courses/"+id+"/publication",Map.of("published",true,"version",1)).statusCode()).isEqualTo(200);
            assertThat(json(student.get("/courses/"+id)).get("lessons").get(0).get("id").asLong()).isEqualTo(b);
            assertThat(client.write("DELETE","/admin/courses/"+id+"?version=2",null).statusCode()).isEqualTo(409);
            assertThat(client.write("PATCH","/admin/lessons/"+b+"/publication",Map.of("published",false)).statusCode()).isEqualTo(200);
            assertThat(json(student.get("/courses/"+id)).get("lessons").size()).isEqualTo(1);
            assertThat(client.write("PATCH","/admin/courses/"+id+"/publication",Map.of("published",false,"version",2)).statusCode()).isEqualTo(200);
            assertThat(client.write("PATCH","/admin/courses/"+id+"/publication",Map.of("published",true,"version",3)).statusCode()).isEqualTo(409);
            assertThat(client.write("DELETE","/admin/courses/"+id+"?version=3",null).statusCode()).isEqualTo(204);
            assertThat(client.get("/admin/lessons/"+a).statusCode()).isEqualTo(200);
        } finally { jdbc.sql("DELETE FROM courses WHERE id=:id").param("id",id).update(); }
    }

    @Test void aiConfigurationIsProtectedValidatedAndAppliedWithoutExposingKey() throws Exception {
        var client=admin();var learner=new Client();String learnerEmail=register(learner); learner.write("POST","/auth/login",Map.of("email",learnerEmail,"password",PASSWORD));
        assertThat(learner.get("/admin/ai-settings").statusCode()).isEqualTo(403);
        assertThat(learner.get("/admin/courses").statusCode()).isEqualTo(403);
        var original=json(client.get("/admin/ai-settings"));
        assertThat(original.has("apiKey")).isFalse();
        var settings=original.get("settings");long version=settings.get("version").asLong();
        var body=Map.of("enabled",false,"model","gemini-2.5-flash","dailyTurns",7,"globalDailyTurns",40,"version",version);
        try {
            assertThat(client.raw("PUT","/admin/ai-settings",body,null).statusCode()).isEqualTo(403);
            assertThat(learner.write("PUT","/admin/ai-settings",body).statusCode()).isEqualTo(403);
            var saved=client.write("PUT","/admin/ai-settings",body);
            assertThat(saved.statusCode()).withFailMessage(saved.body()).isEqualTo(200);
            assertThat(json(saved).get("settings").get("model").asText()).isEqualTo("gemini-2.5-flash");
            assertThat(json(learner.get("/ai/status")).get("reason").asText()).isEqualTo("DISABLED");
            assertThat(json(learner.get("/ai/status")).get("dailyLimit").asInt()).isEqualTo(7);
            assertThat(client.write("PUT","/admin/ai-settings",body).statusCode()).isEqualTo(409);
            assertThat(client.write("PUT","/admin/ai-settings",Map.of("enabled",true,"model","bad-model","dailyTurns",7,"globalDailyTurns",40,"version",version+1)).statusCode()).isEqualTo(400);
            assertThat(client.write("PUT","/admin/ai-settings",Map.of("enabled",true,"model","gemini-2.5-flash","dailyTurns",0,"globalDailyTurns",40,"version",version+1)).statusCode()).isEqualTo(400);
        } finally {
            if(version==0) jdbc.sql("DELETE FROM ai_runtime_settings WHERE id=1").update();
            else jdbc.sql("UPDATE ai_runtime_settings SET enabled=:enabled,model=:model,daily_turns=:daily,global_daily_turns=:global,version=:version WHERE id=1")
                .param("enabled",settings.get("enabled").asBoolean()).param("model",settings.get("model").asText()).param("daily",settings.get("dailyTurns").asInt())
                .param("global",settings.get("globalDailyTurns").asInt()).param("version",version).update();
        }
    }

    @AfterEach void cleanup() {
        for(var email:emails) jdbc.sql("DELETE FROM users WHERE email=:email").param("email",email).update();
        for(var id:lessonIds){jdbc.sql("DELETE FROM lesson_vocabulary WHERE lesson_id=:id").param("id",id).update();jdbc.sql("DELETE FROM lessons WHERE id=:id").param("id",id).update();}
        for(var id:wordIds)jdbc.sql("DELETE FROM vocabulary WHERE id=:id").param("id",id).update();
    }
    @Test void ordinaryUsersCannotUseAdminEndpointsOrAssignTheirOwnRole() throws Exception {
        var client=new Client();
        assertThat(client.get("/admin/vocabulary").statusCode()).isEqualTo(401);
        String email=register(client);
        assertThat(json(client.write("POST","/auth/login",Map.of("email",email,"password",PASSWORD))).get("role").asText()).isEqualTo("USER");
        for(var path:List.of("/admin/vocabulary","/admin/vocabulary/1","/admin/lessons","/admin/lessons/1"))assertThat(client.get(path).statusCode()).isEqualTo(403);
        assertThat(client.write("POST","/admin/vocabulary",word("测试")).statusCode()).isEqualTo(403);
        assertThat(client.write("PUT","/admin/vocabulary/1",word("测试")).statusCode()).isEqualTo(403);
        assertThat(client.write("DELETE","/admin/vocabulary/1",null).statusCode()).isEqualTo(403);
        assertThat(client.write("POST","/admin/lessons",lesson("Denied",99001,List.of())).statusCode()).isEqualTo(403);
        assertThat(client.write("PUT","/admin/lessons/1",lesson("Denied",99001,List.of())).statusCode()).isEqualTo(403);
        assertThat(client.write("DELETE","/admin/lessons/1",null).statusCode()).isEqualTo(403);
        assertThat(client.write("PATCH","/profile",Map.of("displayName","Test","role","ADMIN")).statusCode()).isEqualTo(400);
        assertThat(client.write("POST","/auth/register",Map.of("email","role-injection@example.test","password",PASSWORD,"displayName","Test","role","ADMIN")).statusCode()).isEqualTo(400);
        assertThat(json(client.get("/auth/me")).get("role").asText()).isEqualTo("USER");
    }
    @Test void hiddenLessonsStayEditableAndKeepLearningRecords() throws Exception {
        var root=admin();long word=createWord(root,"隐藏测试");
        var created=root.write("POST","/admin/lessons",lesson("Visibility test",99040,List.of(word)));
        assertThat(created.statusCode()).isEqualTo(201);
        long id=json(created).get("lesson").get("id").asLong();lessonIds.add(id);
        var student=new Client();String email=register(student);
        student.write("POST","/auth/login",Map.of("email",email,"password",PASSWORD));
        assertThat(student.write("POST","/learning-records",Map.of("lessonId",id,"note","Keep me")).statusCode()).isEqualTo(201);
        String path="/admin/lessons/"+id+"/publication";
        assertThat(root.raw("PATCH",path,Map.of("published",false),null).statusCode()).isEqualTo(403);
        assertThat(student.write("PATCH",path,Map.of("published",false)).statusCode()).isEqualTo(403);
        assertThat(root.write("PATCH",path,Map.of()).statusCode()).isEqualTo(400);
        assertThat(root.write("PATCH",path,Map.of("published",false)).statusCode()).isEqualTo(200);
        assertThat(student.get("/lessons/"+id).statusCode()).isEqualTo(404);
        assertThat(student.write("POST","/learning-records/complete",Map.of("lessonId",id)).statusCode()).isEqualTo(404);
        assertThat(student.get("/lessons").body()).doesNotContain("Visibility test");
        assertThat(student.get("/vocabulary/"+word).body()).doesNotContain("Visibility test");
        assertThat(student.get("/vocabulary?lessonId="+id).statusCode()).isEqualTo(404);
        assertThat(student.get("/learning-records").body()).contains("Keep me");
        assertThat(root.get("/admin/lessons").body()).contains("Visibility test");
        assertThat(root.write("PUT","/admin/lessons/"+id,lesson("Edited hidden",99040,List.of(word))).statusCode()).isEqualTo(200);
        assertThat(json(root.get("/admin/lessons/"+id)).get("lesson").get("published").asBoolean()).isFalse();
        assertThat(root.write("PATCH",path,Map.of("published",true)).statusCode()).isEqualTo(200);
        assertThat(student.get("/lessons/"+id).statusCode()).isEqualTo(200);
        assertThat(student.get("/learning-records").body()).contains("Keep me");
    }
    @Test void adminContentCrudPreservesOrderAndRollsBackInvalidChanges() throws Exception {
        var client=admin();
        long first=createWord(client,"测试一"),second=createWord(client,"测试二");
        var updated=client.write("PUT","/admin/vocabulary/"+first,word("修改"));
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(json(client.get("/admin/vocabulary/"+first)).get("hanzi").asText()).isEqualTo("修改");
        var created=client.write("POST","/admin/lessons",lesson("Admin test",99002,List.of(first,second)));
        assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(201);
        long id=json(created).get("lesson").get("id").asLong();lessonIds.add(id);
        assertThat(json(client.get("/lessons/"+id)).get("words").get(0).get("id").asLong()).isEqualTo(first);
        assertThat(client.write("PUT","/admin/lessons/"+id,lesson("Reordered",99002,List.of(second,first))).statusCode()).isEqualTo(200);
        assertThat(json(client.get("/lessons/"+id)).get("words").get(0).get("id").asLong()).isEqualTo(second);
        assertThat(client.write("DELETE","/admin/vocabulary/"+first,null).statusCode()).isEqualTo(409);
        assertThat(client.write("PUT","/admin/lessons/"+id,lesson("Bad",99002,List.of(first,first))).statusCode()).isEqualTo(400);
        assertThat(client.write("PUT","/admin/lessons/"+id,lesson("Bad",99002,List.of(Long.MAX_VALUE))).statusCode()).isEqualTo(400);
        assertThat(client.write("PUT","/admin/lessons/"+id,lesson("Bad",1,List.of(first))).statusCode()).isEqualTo(409);
        var unchanged=json(client.get("/lessons/"+id));
        assertThat(unchanged.get("lesson").get("title").asText()).isEqualTo("Reordered");
        assertThat(unchanged.get("words").size()).isEqualTo(2);
        assertThat(unchanged.get("words").get(0).get("id").asLong()).isEqualTo(second);
        assertThat(client.write("POST","/learning-records",Map.of("lessonId",id,"note","Keep my data")).statusCode()).isEqualTo(201);
        assertThat(client.write("DELETE","/admin/lessons/"+id,null).statusCode()).isEqualTo(409);
        assertThat(json(client.get("/lessons/"+id)).get("words").size()).isEqualTo(2);
        // Remove only this test-created learning record to exercise an allowed lesson deletion.
        jdbc.sql("DELETE FROM learning_records WHERE lesson_id=:id").param("id",id).update();
        assertThat(client.write("DELETE","/admin/lessons/"+id,null).statusCode()).isEqualTo(204);
        assertThat(client.get("/lessons/"+id).statusCode()).isEqualTo(404);
        assertThat(client.write("DELETE","/admin/vocabulary/"+first,null).statusCode()).isEqualTo(204);
        assertThat(client.get("/admin/vocabulary/"+first).statusCode()).isEqualTo(404);
        assertThat(client.write("PUT","/admin/vocabulary/"+first,word("Missing")).statusCode()).isEqualTo(404);
    }
    @Test void adminRequiresCsrfAndCurrentRoleAndValidInput() throws Exception {
        var client=admin();String email=emails.get(0);
        assertThat(client.raw("POST","/admin/vocabulary",word("test"),null).statusCode()).isEqualTo(403);
        assertThat(client.write("POST","/admin/vocabulary",Map.of("hanzi"," ")).statusCode()).isEqualTo(400);
        assertThat(client.write("POST","/admin/lessons",lesson("Invalid",0,List.of())).statusCode()).isEqualTo(400);
        jdbc.sql("UPDATE users SET role='USER' WHERE email=:email").param("email",email).update();
        assertThat(client.get("/admin/vocabulary").statusCode()).isEqualTo(403);
        assertThat(client.write("POST","/admin/vocabulary",word("revoked")).statusCode()).isEqualTo(403);
    }
    Map<String,Object> word(String hanzi){return Map.of("hanzi",hanzi,"pinyin","cè shì","meaningVi","Kiểm thử","exampleHanzi","这是测试。","examplePinyin","Zhè shì cèshì.","exampleMeaningVi","Đây là kiểm thử.");}
    Map<String,Object> lesson(String title,int position,List<Long> ids){return Map.of("title",title,"description","Integration test","position",position,"wordIds",ids);}
    long createWord(Client c,String hanzi)throws Exception{
        var result=c.write("POST","/admin/vocabulary",word(hanzi));assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(201);
        long id=json(result).get("id").asLong();wordIds.add(id);return id;
    }
    String register(Client c)throws Exception{
        String email="admin-test-"+UUID.randomUUID()+"@example.test";emails.add(email);
        var result=c.write("POST","/auth/register",Map.of("email",email,"password",PASSWORD,"displayName","Admin test"));
        assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(201);FoundationTestSupport.graduate(jdbc,email);return email;
    }
    Client admin()throws Exception{
        var c=new Client();String email=register(c);
        jdbc.sql("UPDATE users SET role='ADMIN' WHERE email=:email").param("email",email).update();
        assertThat(c.write("POST","/auth/login",Map.of("email",email,"password",PASSWORD)).statusCode()).isEqualTo(200);return c;
    }
    JsonNode json(HttpResponse<String> r){return mapper.readTree(r.body());}
    class Client{
        String cookie="";HttpClient http=HttpClient.newHttpClient();
        HttpResponse<String> get(String path)throws Exception{return raw("GET",path,null,null);}
        HttpResponse<String> write(String method,String path,Object body)throws Exception{return raw(method,path,body,json(get("/auth/csrf")).get("token").asText());}
        HttpResponse<String> raw(String method,String path,Object body,String csrf)throws Exception{
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path));
            if(!cookie.isEmpty())request.header("Cookie",cookie);if(csrf!=null)request.header("X-CSRF-TOKEN",csrf);
            request.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
            for(var header:response.headers().allValues("set-cookie"))if(header.startsWith("JSESSIONID="))cookie=header.split(";",2)[0];return response;
        }
    }
}
