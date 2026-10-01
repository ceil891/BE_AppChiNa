package com.chineselearning;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthOwnershipTests {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    final List<String> createdEmails = new ArrayList<>();
    static final String PASSWORD = "Test-only-password-17!";

    @AfterEach void cleanup() {
        for (var email : createdEmails) jdbc.sql("DELETE FROM users WHERE email=:email").param("email", email).update();
    }

    @Test void sessionSurvivesNewClientAndLogoutRevokesOldCookie() throws Exception {
        var a = new Browser();
        String email = register(a);
        String anonymousCookie = a.cookie;
        String oldCsrf = a.csrf();
        var login = a.write("POST", "/auth/login", Map.of("email",email,"password",PASSWORD));
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(a.cookie).isNotEqualTo(anonymousCookie);
        assertThat(login.headers().allValues("set-cookie").toString()).contains("HttpOnly", "SameSite=Lax");
        assertThat(a.raw("PATCH","/profile",Map.of("displayName","Rejected"),oldCsrf).statusCode()).isEqualTo(403);
        var reloaded = new Browser(); reloaded.cookie = a.cookie;
        assertThat(reloaded.get("/auth/me").statusCode()).isEqualTo(200);
        assertThat(json(reloaded.get("/auth/me")).get("email").asText()).isEqualTo(email);
        assertThat(a.write("PATCH","/profile",Map.of("displayName","Tên mới")).statusCode()).isEqualTo(200);
        assertThat(json(reloaded.get("/profile")).get("displayName").asText()).isEqualTo("Tên mới");
        assertThat(a.write("POST","/auth/logout",null).statusCode()).isEqualTo(204);
        assertThat(reloaded.get("/auth/me").statusCode()).isEqualTo(401);
        assertThat(reloaded.get("/learning-records").statusCode()).isEqualTo(401);
        assertThat(a.get("/auth/me").statusCode()).isEqualTo(401);
        // The same browser can obtain a fresh token and sign in again after logout.
        assertThat(a.write("POST","/auth/login",Map.of("email",email,"password",PASSWORD)).statusCode()).isEqualTo(200);
    }

    @Test void twoAccountsCannotReadOrModifyEachOthersLearningRecords() throws Exception {
        var a = new Browser(); var b = new Browser();
        String emailA=register(a), emailB=register(b);
        login(a,emailA); login(b,emailB);
        var recordA = a.write("POST","/learning-records",Map.of("lessonId",1,"note","Private A"));
        var recordB = b.write("POST","/learning-records",Map.of("lessonId",1,"note","Private B"));
        assertThat(recordA.statusCode()).isEqualTo(201); assertThat(recordB.statusCode()).isEqualTo(201);
        String idA=json(recordA).get("id").asText(), idB=json(recordB).get("id").asText();
        assertThat(a.get("/learning-records").body()).contains(idA).doesNotContain(idB,"Private B");
        assertThat(b.get("/learning-records").body()).contains(idB).doesNotContain(idA,"Private A");
        assertThat(a.get("/learning-records/"+idB).statusCode()).isEqualTo(404);
        assertThat(b.get("/learning-records/"+idA).statusCode()).isEqualTo(404);
        assertThat(a.write("PATCH","/learning-records/"+idB,Map.of("note","stolen")).statusCode()).isEqualTo(404);
        assertThat(b.write("PATCH","/learning-records/"+idA,Map.of("note","stolen")).statusCode()).isEqualTo(404);
        assertThat(a.get("/learning-records/"+idA).body()).contains("Private A");
        assertThat(b.get("/learning-records/"+idB).body()).contains("Private B");
        assertThat(a.write("PATCH","/learning-records/"+idA,Map.of("note","Updated A")).statusCode()).isEqualTo(200);
        assertThat(a.get("/learning-records/"+idA).body()).contains("Updated A");
        assertThat(a.write("POST","/learning-records",Map.of("lessonId",1,"note","")).statusCode()).isEqualTo(409);
        assertThat(a.write("POST","/learning-records",Map.of("lessonId",99999,"note","")).statusCode()).isEqualTo(404);
        String userB=json(b.get("/auth/me")).get("id").asText();
        assertThat(a.write("POST","/learning-records",Map.of("lessonId",2,"note","","userId",userB)).statusCode()).isEqualTo(400);
        assertThat(a.write("PATCH","/profile",Map.of("displayName","Impersonated","id",userB)).statusCode()).isEqualTo(400);
        assertThat(b.get("/profile").body()).doesNotContain("Impersonated");
    }

    @Test void validationAndCredentialErrorsUseSafeProblemDetails() throws Exception {
        var a = new Browser(); String email=register(a);
        var bad = a.write("POST","/auth/register",Map.of("email","bad-email","password","short","displayName"," "));
        assertProblem(bad,400); assertThat(json(bad).get("errors").has("password")).isTrue();
        assertThat(a.write("POST","/auth/register",Map.of("email","  "+email.toUpperCase(Locale.ROOT)+"  ","password",PASSWORD,"displayName","Duplicate")).statusCode()).isEqualTo(409);
        var wrong = a.write("POST","/auth/login",Map.of("email",email,"password","wrong-password"));
        assertProblem(wrong,401);
        var missing = a.write("POST","/auth/login",Map.of("email","missing-"+UUID.randomUUID()+"@example.test","password",PASSWORD));
        assertThat(json(missing).get("detail")).isEqualTo(json(wrong).get("detail"));
        assertThat(a.get("/auth/me").statusCode()).isEqualTo(401);
        login(a,email);
        assertProblem(a.write("PATCH","/profile",Map.of("displayName"," ")),400);
        assertProblem(a.write("POST","/learning-records",Map.of("lessonId",0,"note","")),400);
        assertProblem(a.write("POST","/learning-records",Map.of("lessonId",1,"note","x".repeat(2001))),400);
        String hash=jdbc.sql("SELECT password_hash FROM users WHERE email=:email").param("email",email).query(String.class).single();
        assertThat(hash).isNotEqualTo(PASSWORD); assertThat(encoder.matches(PASSWORD,hash)).isTrue();
        assertThat(a.get("/profile").body()).doesNotContain(hash,"passwordHash","password_hash",PASSWORD);
    }

    @Test void csrfAndAuthenticationAreRequiredForPrivateActions() throws Exception {
        var a = new Browser();
        assertProblem(a.get("/profile"),401);
        assertProblem(a.get("/learning-records"),401);
        assertProblem(a.raw("POST","/auth/register",Map.of("email","none@example.test","password",PASSWORD,"displayName","Test"),null),403);
        assertProblem(a.write("POST","/learning-records",Map.of("lessonId",1,"note","")),401);
        assertProblem(a.write("POST","/learning-records/complete",Map.of("lessonId",1)),401);
        String email=register(a); login(a,email);
        assertProblem(a.raw("PATCH","/profile",Map.of("displayName","No CSRF"),null),403);
        assertProblem(a.raw("POST","/auth/logout",null,null),403);
        assertProblem(a.raw("POST","/learning-records/complete",Map.of("lessonId",1),null),403);
        assertThat(a.get("/auth/me").statusCode()).isEqualTo(200);
    }

    @Test void lessonCompletionIsPrivateIdempotentAndPreservesNotes() throws Exception {
        var a = new Browser(); var b = new Browser();
        login(a,register(a)); login(b,register(b));
        var saved = a.write("POST","/learning-records",Map.of("lessonId",1,"note","Keep this note"));
        String originalId = json(saved).get("id").asText();
        assertThat(json(saved).get("completedAt").isNull()).isTrue();
        var completed = a.write("POST","/learning-records/complete",Map.of("lessonId",1));
        assertThat(completed.statusCode()).withFailMessage(completed.body()).isEqualTo(200);
        assertThat(json(completed).get("id").asText()).isEqualTo(originalId);
        assertThat(json(completed).get("note").asText()).isEqualTo("Keep this note");
        assertThat(json(completed).get("completedAt").isNull()).isFalse();
        assertThat(json(a.write("POST","/learning-records/complete",Map.of("lessonId",1)))).isEqualTo(json(completed));
        assertThat(json(a.get("/learning-records")).size()).isEqualTo(1);
        var reloaded = new Browser(); reloaded.cookie = a.cookie;
        assertThat(json(reloaded.get("/learning-records/" + originalId))).isEqualTo(json(completed));
        assertThat(json(b.get("/learning-records")).size()).isZero();
        assertThat(b.get("/learning-records/" + originalId).statusCode()).isEqualTo(404);
        assertThat(b.write("PATCH","/learning-records/" + originalId,Map.of("note","stolen")).statusCode()).isEqualTo(404);
        String userA = json(a.get("/auth/me")).get("id").asText();
        assertProblem(b.write("POST","/learning-records/complete",Map.of("lessonId",1,"userId",userA)),400);
        var ownB = b.write("POST","/learning-records/complete",Map.of("lessonId",1));
        assertThat(ownB.statusCode()).isEqualTo(200);
        assertThat(json(ownB).get("id").asText()).isNotEqualTo(originalId);
        assertThat(a.get("/learning-records").body()).doesNotContain(json(ownB).get("id").asText());
        assertProblem(a.write("POST","/learning-records/complete",Map.of("lessonId",9999999)),404);
        assertProblem(a.write("POST","/learning-records/complete",Map.of("lessonId",0)),400);
        a.write("POST","/auth/logout",null);
        assertProblem(reloaded.get("/learning-records/" + originalId),401);
    }

    String register(Browser b) throws Exception {
        String email="auth-test-"+UUID.randomUUID()+"@example.test";
        createdEmails.add(email);
        var response=b.write("POST","/auth/register",Map.of("email",email,"password",PASSWORD,"displayName","Test account"));
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        FoundationTestSupport.graduate(jdbc,email);
        return email;
    }
    void login(Browser b,String email) throws Exception {
        assertThat(b.write("POST","/auth/login",Map.of("email",email,"password",PASSWORD)).statusCode()).isEqualTo(200);
    }
    JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
    void assertProblem(HttpResponse<String> response,int status) {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/problem+json");
        assertThat(json(response).get("status").asInt()).isEqualTo(status);
        assertThat(json(response).has("detail")).isTrue();
        assertThat(json(response).has("instance")).isTrue();
    }
    class Browser {
        String cookie="";
        HttpClient client=HttpClient.newHttpClient();
        HttpResponse<String> get(String path) throws Exception { return raw("GET",path,null,null); }
        String csrf() throws Exception { return json(get("/auth/csrf")).get("token").asText(); }
        HttpResponse<String> write(String method,String path,Object body) throws Exception { return raw(method,path,body,csrf()); }
        HttpResponse<String> raw(String method,String path,Object body,String csrf) throws Exception {
            var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path));
            if (!cookie.isEmpty()) req.header("Cookie",cookie);
            if (csrf!=null) req.header("X-CSRF-TOKEN",csrf);
            req.header("Content-Type","application/json");
            req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var res=client.send(req.build(),HttpResponse.BodyHandlers.ofString());
            for(var header:res.headers().allValues("set-cookie")) if(header.startsWith("JSESSIONID=")) cookie=header.split(";",2)[0];
            return res;
        }
    }
}
