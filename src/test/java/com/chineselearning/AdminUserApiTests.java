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
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminUserApiTests {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PasswordEncoder encoder;
    final List<String> emails=new ArrayList<>();
    static final String PASSWORD="Account-test-password-17!";
    static final String NEW_PASSWORD="Replacement-password-17!";

    @AfterEach void cleanup() {
        for(var email:emails) jdbc.sql("DELETE FROM users WHERE email=:email").param("email",email).update();
    }

    @Test void accountManagementRequiresAdminAndCsrf() throws Exception {
        var anonymous=new Client();
        status(anonymous.get("/admin/users"),401);
        var admin=admin(); var learner=create(admin,"USER");
        var client=login(learner,PASSWORD);
        String path="/admin/users/"+learner.get("id").asText();
        status(client.get("/admin/users"),403); status(client.get(path),403);
        status(client.write("POST","/admin/users",Map.of()),403);
        status(client.write("PUT",path,update("Escalated","ADMIN",true)),403);
        status(client.write("PATCH",path+"/status",Map.of("enabled",false)),403);
        status(client.write("POST",path+"/password",Map.of("password",NEW_PASSWORD)),403);
        status(client.write("DELETE",path,null),403);
        status(admin.raw("DELETE",path,null,null),403);
        status(admin.get(path),200);
    }

    @Test void crudSearchPaginationAndResponsesKeepPasswordsPrivate() throws Exception {
        var admin=admin(); var target=create(admin,"USER");
        String id=target.get("id").asText(), email=target.get("email").asText(), path="/admin/users/"+id;
        assertThat(target.get("enabled").asBoolean()).isTrue();
        assertThat(target.get("learningRecordCount").asLong()).isZero();
        var client=login(target,PASSWORD);
        status(client.write("POST","/learning-records",Map.of("lessonId",1,"note","private note")),201);
        status(admin.write("PUT",path,update("Tên đã sửa","USER",true)),200);
        assertThat(json(client.get("/auth/me")).get("displayName").asText()).isEqualTo("Tên đã sửa");
        var found=json(admin.get("/admin/users?q="+email+"&role=USER&status=active&size=1"));
        assertThat(found.get("total").asLong()).isEqualTo(1);
        assertThat(found.get("items").size()).isEqualTo(1);
        assertThat(found.get("items").get(0).get("learningRecordCount").asLong()).isEqualTo(1);
        assertThat(json(admin.get("/admin/users?q="+email+"&size=1&page=1")).get("items").isEmpty()).isTrue();
        assertThat(json(admin.get("/admin/users?q="+email+"&role=ADMIN")).get("total").asLong()).isZero();
        String hash=jdbc.sql("SELECT password_hash FROM users WHERE id=:id").param("id",UUID.fromString(id)).query(String.class).single();
        assertThat(encoder.matches(PASSWORD,hash)).isTrue();
        assertThat(admin.get(path).body()).doesNotContain(hash,PASSWORD,"passwordHash","password_hash","sessionVersion","private note");
        assertThat(admin.get("/admin/users").body()).doesNotContain(hash,PASSWORD,"passwordHash","password_hash","sessionVersion","private note");
    }

    @Test void invalidAndDuplicateInputsHaveProblemDetailsWithoutChangingAccounts() throws Exception {
        var admin=admin(); var target=create(admin,"USER");
        String path="/admin/users/"+target.get("id").asText();
        var invalid=admin.write("POST","/admin/users",Map.of("email","invalid","displayName"," ","password","short","role","USER"));
        status(invalid,400);
        assertThat(json(invalid).get("errors").has("email")).isTrue();
        assertThat(json(invalid).get("errors").has("password")).isTrue();
        status(admin.write("POST","/admin/users",Map.of("email","  "+target.get("email").asText().toUpperCase(Locale.ROOT)+"  ","displayName","Duplicate","password",PASSWORD,"role","USER")),409);
        status(admin.write("PUT",path,update(" ","USER",true)),400);
        status(admin.write("PUT",path,update("Bad role","OWNER",true)),400);
        status(admin.write("PUT",path,Map.of("displayName","Bad","role","USER")),400);
        status(admin.write("PUT",path,Map.of("displayName","Bad","role","USER","enabled",true,"email","changed@example.test")),400);
        status(admin.write("POST",path+"/password",Map.of("password","short")),400);
        status(admin.write("PATCH",path+"/status",Map.of()),400);
        for(var query:List.of("size=0","size=51","page=-1","role=OWNER","status=bad","q="+"a".repeat(101)))status(admin.get("/admin/users?"+query),400);
        status(admin.get("/admin/users/not-a-uuid"),400);
        String missing="/admin/users/"+UUID.randomUUID();
        status(admin.get(missing),404);
        status(admin.write("PUT",missing,update("Missing","USER",true)),404);
        status(admin.write("DELETE",missing,null),404);
        assertThat(json(admin.get(path)).get("displayName").asText()).isEqualTo("Test learner");
    }

    @Test void lockingAndUnlockingNeverResurrectsAnOldSession() throws Exception {
        var admin=admin(); var target=create(admin,"USER");
        String path="/admin/users/"+target.get("id").asText();
        var active=login(target,PASSWORD); var dormant=login(target,PASSWORD);
        status(admin.write("PATCH",path+"/status",Map.of("enabled",false)),200);
        status(active.get("/auth/me"),401);
        status(new Client().write("POST","/auth/login",credentials(target,PASSWORD)),401);
        assertThat(json(admin.get("/admin/users?q="+target.get("email").asText()+"&status=locked")).get("total").asLong()).isEqualTo(1);
        status(admin.write("PATCH",path+"/status",Map.of("enabled",true)),200);
        // This cookie was never presented while locked; enabling must not revive it.
        status(dormant.get("/auth/me"),401);
        status(login(target,PASSWORD).get("/auth/me"),200);
        assertThat(json(admin.get(path)).get("role").asText()).isEqualTo("USER");
        assertThat(json(admin.get(path)).get("displayName").asText()).isEqualTo("Test learner");
    }

    @Test void passwordAndRoleChangesRevokeEveryExistingSession() throws Exception {
        var admin=admin(); var target=create(admin,"USER");
        String path="/admin/users/"+target.get("id").asText();
        var a=login(target,PASSWORD); var b=login(target,PASSWORD);
        status(admin.write("POST",path+"/password",Map.of("password",NEW_PASSWORD)),204);
        status(a.get("/profile"),401); status(b.get("/auth/me"),401);
        status(new Client().write("POST","/auth/login",credentials(target,PASSWORD)),401);
        var promoted=login(target,NEW_PASSWORD);
        status(admin.write("PUT",path,update("Promoted","ADMIN",true)),200);
        status(promoted.get("/auth/me"),401);
        promoted=login(target,NEW_PASSWORD);
        assertThat(json(promoted.get("/auth/me")).get("role").asText()).isEqualTo("ADMIN");
        status(promoted.get("/admin/users"),200);
        status(admin.write("PUT",path,update("Demoted","USER",true)),200);
        status(promoted.get("/admin/users"),401);
        status(login(target,NEW_PASSWORD).get("/admin/users"),403);
    }

    @Test void deletingAccountOnlyDeletesItsDataAndSameEmailCannotReuseOldSession() throws Exception {
        var admin=admin(); var a=create(admin,"USER"); var b=create(admin,"USER");
        var clientA=login(a,PASSWORD); var clientB=login(b,PASSWORD);
        var dormant=login(a,PASSWORD);
        String path="/admin/users/"+a.get("id").asText();
        status(clientA.write("POST","/learning-records",Map.of("lessonId",1,"note","Private A")),201);
        status(clientB.write("POST","/learning-records",Map.of("lessonId",1,"note","Private B")),201);
        status(admin.write("DELETE",path,null),204);
        status(admin.get(path),404); status(clientA.get("/auth/me"),401);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM learning_records WHERE user_id=:id").param("id",UUID.fromString(a.get("id").asText())).query(Long.class).single()).isZero();
        assertThat(clientB.get("/learning-records").body()).contains("Private B").doesNotContain("Private A");
        status(admin.write("POST","/admin/users",Map.of("email",a.get("email").asText(),"displayName","New account","password",PASSWORD,"role","USER")),201);
        status(dormant.get("/auth/me"),401);
    }

    @Test void adminCannotDemoteLockOrDeleteSelfButCanChangeOwnNameAndPassword() throws Exception {
        var admin=admin(); var self=json(admin.get("/auth/me"));
        String path="/admin/users/"+self.get("id").asText();
        status(admin.write("PUT",path,update("Self","USER",true)),409);
        status(admin.write("PUT",path,update("Self","ADMIN",false)),409);
        status(admin.write("PATCH",path+"/status",Map.of("enabled",false)),409);
        status(admin.write("DELETE",path,null),409);
        status(admin.write("PUT",path,update("New admin name","ADMIN",true)),200);
        assertThat(json(admin.get("/auth/me")).get("displayName").asText()).isEqualTo("New admin name");
        status(admin.write("POST",path+"/password",Map.of("password",NEW_PASSWORD)),204);
        status(admin.get("/auth/me"),401);
        status(login(self,NEW_PASSWORD).get("/admin/users"),200);
    }

    @Test void concurrentAdminDemotionsCannotRemoveBothAdministrators() throws Exception {
        var a=admin(); var accountA=json(a.get("/auth/me"));
        var accountB=create(a,"ADMIN"); var b=login(accountB,PASSWORD);
        String csrfA=a.csrf(), csrfB=b.csrf();
        var start=new CountDownLatch(1);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var first=executor.submit(()->{start.await();return a.raw("PUT","/admin/users/"+accountB.get("id").asText(),update("B","USER",true),csrfA).statusCode();});
            var second=executor.submit(()->{start.await();return b.raw("PUT","/admin/users/"+accountA.get("id").asText(),update("A","USER",true),csrfB).statusCode();});
            start.countDown();
            var statuses=List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
            assertThat(statuses).contains(200);
            assertThat(statuses.stream().filter(s->s==200).count()).isEqualTo(1);
            assertThat(statuses).allMatch(s->s==200 || s==401 || s==403 || s==409);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM users WHERE id IN (:a,:b) AND role='ADMIN' AND enabled")
                .param("a",UUID.fromString(accountA.get("id").asText())).param("b",UUID.fromString(accountB.get("id").asText())).query(Long.class).single()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    Map<String,Object> update(String name,String role,boolean enabled){return Map.of("displayName",name,"role",role,"enabled",enabled);}
    Map<String,Object> credentials(JsonNode account,String password){return Map.of("email",account.get("email").asText(),"password",password);}
    String email(){String value="account-test-"+UUID.randomUUID()+"@example.test";emails.add(value);return value;}
    JsonNode create(Client admin,String role)throws Exception{
        var result=admin.write("POST","/admin/users",Map.of("email",email(),"displayName","Test learner","password",PASSWORD,"role",role));
        status(result,201);return json(result);
    }
    Client login(JsonNode account,String password)throws Exception{
        var c=new Client();status(c.write("POST","/auth/login",credentials(account,password)),200);return c;
    }
    Client admin()throws Exception{
        var c=new Client();String email=email();
        status(c.write("POST","/auth/register",Map.of("email",email,"displayName","Admin test","password",PASSWORD)),201);
        jdbc.sql("UPDATE users SET role='ADMIN' WHERE email=:email").param("email",email).update();
        status(c.write("POST","/auth/login",Map.of("email",email,"password",PASSWORD)),200);return c;
    }
    JsonNode json(HttpResponse<String> response){return mapper.readTree(response.body());}
    void status(HttpResponse<String> response,int expected){
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(expected);
        if(expected>=400){
            assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/problem+json");
            assertThat(json(response).get("status").asInt()).isEqualTo(expected);
        }
    }
    class Client {
        String cookie=""; HttpClient http=HttpClient.newHttpClient();
        HttpResponse<String> get(String path)throws Exception{return raw("GET",path,null,null);}
        String csrf()throws Exception{return json(get("/auth/csrf")).get("token").asText();}
        HttpResponse<String> write(String method,String path,Object body)throws Exception{return raw(method,path,body,csrf());}
        HttpResponse<String> raw(String method,String path,Object body,String csrf)throws Exception{
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path));
            if(!cookie.isEmpty())request.header("Cookie",cookie);
            if(csrf!=null)request.header("X-CSRF-TOKEN",csrf);
            request.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
            for(var header:response.headers().allValues("set-cookie"))if(header.startsWith("JSESSIONID="))cookie=header.split(";",2)[0];
            return response;
        }
    }
}
