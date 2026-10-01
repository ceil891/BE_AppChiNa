package com.chineselearning;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AccountSecurityApiTests {
    static final Path MAIL=Path.of("../.tools/test-mail-"+UUID.randomUUID()).toAbsolutePath().normalize();
    @DynamicPropertySource static void mail(DynamicPropertyRegistry r) { r.add("account.mail.mode",()->"local");r.add("account.mail.local-directory",()->MAIL.toString()); }
    @LocalServerPort int port; @Autowired JdbcClient jdbc; @Autowired ObjectMapper mapper;
    AdminUserApiTests api;
    @BeforeEach void setup(){api=new AdminUserApiTests();api.port=port;api.jdbc=jdbc;api.mapper=mapper;}
    @AfterEach void cleanup(){api.cleanup();}
    @AfterAll static void cleanMail() throws Exception { if(Files.exists(MAIL)){try(var files=Files.list(MAIL)){for(var p:files.toList())Files.delete(p);}Files.delete(MAIL);} }
    String token(String email,String purpose) throws Exception {
        try(var files=Files.list(MAIL)){for(var p:files.toList()){String text=Files.readString(p);if(text.contains("To: "+email+"\n") && text.contains("/account/"+purpose+"#token="))return text.split("#token=")[1].split("\\s")[0];}}
        throw new AssertionError("No test email");
    }
    @Test void resetIsOneUseAndRevokesAllSessionsWithoutEnumeratingAccounts() throws Exception {
        var admin=api.admin();var account=api.create(admin,"USER");String email=account.get("email").asText();
        var a=api.login(account,AdminUserApiTests.PASSWORD);var b=api.login(account,AdminUserApiTests.PASSWORD);var guest=api.new Client();
        api.status(guest.raw("POST","/account/forgot",Map.of("email",email),null),403);
        var known=guest.write("POST","/account/forgot",Map.of("email",email));api.status(known,200);
        var unknown=guest.write("POST","/account/forgot",Map.of("email","missing-"+UUID.randomUUID()+"@example.test"));api.status(unknown,200);assertThat(known.body()).isEqualTo(unknown.body());
        String token=token(email,"reset");
        assertThat(jdbc.sql("SELECT token_hash FROM account_tokens WHERE user_id=:id").param("id",UUID.fromString(account.get("id").asText())).query(String.class).single()).doesNotContain(token);
        api.status(guest.write("POST","/account/verify",Map.of("token",token)),400);
        api.status(guest.write("POST","/account/reset",Map.of("token",token,"password","short")),400);
        api.status(guest.write("POST","/account/reset",Map.of("token",token,"password",AdminUserApiTests.NEW_PASSWORD)),204);
        api.status(guest.write("POST","/account/reset",Map.of("token",token,"password",AdminUserApiTests.PASSWORD)),400);
        api.status(a.get("/auth/me"),401);api.status(b.get("/auth/me"),401);
        api.status(api.login(account,AdminUserApiTests.NEW_PASSWORD).get("/auth/me"),200);
    }
    @Test void verificationRequiresValidUnexpiredSingleUseToken() throws Exception {
        var admin=api.admin();var account=api.create(admin,"USER");var user=api.login(account,AdminUserApiTests.PASSWORD);String email=account.get("email").asText();
        assertThat(api.json(user.get("/account/status")).get("verified").asBoolean()).isFalse();
        api.status(user.write("POST","/account/verification-email",null),204);String token=token(email,"verify");
        api.status(user.write("POST","/account/verify",Map.of("token",token)),204);
        assertThat(api.json(user.get("/account/status")).get("verified").asBoolean()).isTrue();
        api.status(user.write("POST","/account/verify",Map.of("token",token)),400);
        api.status(user.write("POST","/account/forgot",Map.of("email",email)),200);String reset=token(email,"reset");
        jdbc.sql("UPDATE account_tokens SET expires_at=now()-interval '1 second' WHERE user_id=:id").param("id",UUID.fromString(account.get("id").asText())).update();
        api.status(user.write("POST","/account/reset",Map.of("token",reset,"password",AdminUserApiTests.NEW_PASSWORD)),400);
    }
    @Test void sessionsAreOwnerScopedAndRevocationIsEffectiveOnNextRequest() throws Exception {
        var admin=api.admin();var account=api.create(admin,"USER");var a=api.login(account,AdminUserApiTests.PASSWORD);var b=api.login(account,AdminUserApiTests.PASSWORD);
        var list=api.json(a.get("/account/sessions"));assertThat(list.size()).isEqualTo(2);String otherId="";
        for(var s:list)if(!s.get("current").asBoolean())otherId=s.get("id").asText();
        assertThat(otherId).isNotEmpty();api.status(admin.write("DELETE","/account/sessions/"+otherId,null),204);api.status(b.get("/auth/me"),200);
        api.status(a.write("DELETE","/account/sessions/"+otherId,null),204);api.status(b.get("/auth/me"),401);api.status(a.get("/auth/me"),200);
        api.status(a.write("POST","/auth/logout",null),204);api.status(a.get("/account/sessions"),401);
    }
    @Test void roadmapUsesEditorialOrderAndResumesOnlyTheOwnersSkillAttempt() throws Exception {
        var admin=api.admin();var account=api.createGraduate(admin);var user=api.login(account,AdminUserApiTests.PASSWORD);
        var other=api.login(api.create(admin,"USER"),AdminUserApiTests.PASSWORD);var guest=api.new Client();
        var exercises=api.json(user.get("/skills/exercises"));
        String exercise=exercises.get(0).get("id").asText(), skill=exercises.get(0).get("skill").asText();
        String expected="/skills?skill="+skill+"&exercise="+exercise;
        assertThat(guest.get("/roadmap").body()).contains(expected);
        var response=user.write("POST","/skills/attempts",Map.of("exerciseId",exercise,"requestId",UUID.randomUUID()));
        String attempt=api.json(response).get("summary").get("id").asText();
        String path="/skills/attempts/"+attempt;
        assertThat(user.get("/roadmap").body()).contains(path,"STARTED");
        assertThat(other.get("/roadmap").body()).doesNotContain(path).contains(expected);
        assertThat(guest.get("/roadmap").body()).doesNotContain(path).contains(expected);
        var actual=new ArrayList<String>();
        for(var step:api.json(user.get("/roadmap")))if(step.get("stage").asText().equals("Từ vựng chủ đề"))actual.add(step.get("path").asText());
        var ordered=jdbc.sql("SELECT '/lessons/'||l.id FROM lessons l WHERE l.published AND EXISTS(SELECT 1 FROM lesson_vocabulary v WHERE v.lesson_id=l.id) ORDER BY l.position,l.id").query(String.class).list();
        assertThat(actual).containsExactlyElementsOf(ordered);
    }
    @Test void roadmapAndAdminOversightRespectIdentityAndPermissions() throws Exception {
        var admin=api.admin();var account=api.create(admin,"USER");var user=api.login(account,AdminUserApiTests.PASSWORD);var guest=api.new Client();
        api.status(guest.get("/roadmap"),200);assertThat(api.json(guest.get("/roadmap")).size()).isGreaterThan(6);
        api.status(user.get("/admin/learner-progress"),403);api.status(admin.get("/admin/learner-progress?inactiveDays=7"),200);
        api.status(admin.get("/admin/learner-progress/"+account.get("id").asText()),200);
        api.status(admin.get("/admin/learner-progress?inactiveDays=-1"),400);
        api.status(user.get("/roadmap"),200);
        jdbc.sql("UPDATE users SET role='USER',session_version=session_version+1 WHERE email=:email").param("email",api.json(admin.get("/auth/me")).get("email").asText()).update();
        api.status(admin.get("/admin/learner-progress"),401);
    }
}
