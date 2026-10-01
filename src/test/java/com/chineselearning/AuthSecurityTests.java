package com.chineselearning;

import com.chineselearning.modules.auth.repository.AuthLimitRepository;
import com.chineselearning.modules.auth.service.AuthThrottle;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthSecurityTests {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc; @Autowired ObjectMapper mapper;
    @Autowired TestClock clock; @Autowired AuthLimitRepository limits;
    AdminUserApiTests api;
    static final String OLD=AdminUserApiTests.PASSWORD, NEW="My-new-password-2026!";
    @TestConfiguration static class Config { @Bean @Primary TestClock authTestClock(){return new TestClock();} }
    static class TestClock extends Clock {
        volatile Instant now=Instant.parse("2026-09-15T06:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(now,zone);}
        public Instant instant(){return now;}
    }
    @BeforeEach void setup() {
        api=new AdminUserApiTests();api.port=port;api.jdbc=jdbc;api.mapper=mapper;
        // Only the isolated chinann_test schema is configured in this context.
        jdbc.sql("DELETE FROM auth_request_limits").update();clock.now=Instant.parse("2026-09-15T06:00:00Z");
    }
    @AfterEach void cleanup() { api.cleanup();jdbc.sql("DELETE FROM auth_request_limits").update(); }
    Map<String,String> change(String current,String next){return Map.of("currentPassword",current,"newPassword",next);}

    @Test void changingOwnPasswordRevokesEveryOldSessionAndKeepsOtherAccountAndLearning()throws Exception {
        var admin=api.admin();var profile=api.create(admin,"USER");var other=api.create(admin,"USER");
        var a=api.login(profile,OLD);var second=api.login(profile,OLD);var b=api.login(other,OLD);
        var record=api.json(a.write("POST","/learning-records",Map.of("lessonId",1,"note","Ghi chú trước khi đổi mật khẩu")));
        var wrong=a.write("POST","/auth/password",change("wrong-password",NEW));api.status(wrong,400);
        assertThat(api.json(wrong).get("errors").has("currentPassword")).isTrue();api.status(a.get("/auth/me"),200);
        String cookie=a.cookie;
        api.status(a.write("POST","/auth/password",change(OLD,NEW)),204);
        api.status(a.get("/auth/me"),401);api.status(second.get("/learning-records"),401);api.status(b.get("/auth/me"),200);
        var replay=api.new Client();replay.cookie=cookie;api.status(replay.get("/auth/me"),401);
        api.status(api.new Client().write("POST","/auth/login",api.credentials(profile,OLD)),401);
        var fresh=api.login(profile,NEW);
        assertThat(api.json(fresh.get("/auth/me")).get("role").asText()).isEqualTo("USER");
        assertThat(fresh.get("/learning-records/"+record.get("id").asText()).body()).contains("Ghi chú trước khi đổi mật khẩu");
        api.status(b.get("/learning-records/"+record.get("id").asText()),404);
    }
    @Test void passwordChangeRequiresSessionCsrfAndCurrentPasswordAndRejectsTargetIds()throws Exception {
        api.status(api.new Client().write("POST","/auth/password",change(OLD,NEW)),401);
        var admin=api.admin();var profile=api.create(admin,"USER");var a=api.login(profile,OLD);
        api.status(a.raw("POST","/auth/password",change(OLD,NEW),null),403);
        api.status(a.write("POST","/auth/password",Map.of("currentPassword",OLD,"newPassword",NEW,"userId",profile.get("id").asText())),400);
        api.status(a.write("POST","/auth/password",change(OLD,"short")),400);
        api.status(a.write("POST","/auth/password",change(OLD,OLD)),400);
        api.status(api.login(profile,OLD).get("/auth/me"),200);
    }
    @Test void concurrentPasswordChangesOnlyCommitOnce()throws Exception {
        var admin=api.admin();var profile=api.create(admin,"USER");var a=api.login(profile,OLD);var b=api.login(profile,OLD);
        UUID id=UUID.fromString(profile.get("id").asText());
        long before=jdbc.sql("SELECT session_version FROM users WHERE id=:id").param("id",id).query(Long.class).single();
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            String csrfA=a.csrf(),csrfB=b.csrf();
            var first=pool.submit(()->{start.await();return a.raw("POST","/auth/password",change(OLD,NEW),csrfA).statusCode();});
            var second=pool.submit(()->{start.await();return b.raw("POST","/auth/password",change(OLD,NEW+"2"),csrfB).statusCode();});
            start.countDown();var statuses=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
            assertThat(statuses.stream().filter(s->s==204).count()).isEqualTo(1);
            assertThat(statuses).allMatch(s->Set.of(204,401,403,409).contains(s));
            assertThat(jdbc.sql("SELECT session_version FROM users WHERE id=:id").param("id",id).query(Long.class).single()).isEqualTo(before+1);
            api.status(api.login(profile,statuses.get(0)==204?NEW:NEW+"2").get("/auth/me"),200);
        } finally {pool.shutdownNow();}
    }
    @Test void loginLimitsNormalizeEmailPersistAcrossClientsAndExpireWithoutExtendingOnRejection()throws Exception {
        var admin=api.admin();var profile=api.create(admin,"USER");String email=profile.get("email").asText();
        for(int i=0;i<10;i++) api.status(api.new Client().write("POST","/auth/login",Map.of("email",i%2==0?"  "+email.toUpperCase(Locale.ROOT)+"  ":email,"password","wrong")),401);
        var blocked=api.new Client().write("POST","/auth/login",api.credentials(profile,OLD));api.status(blocked,429);
        assertThat(blocked.headers().firstValue("Retry-After")).contains("900");
        String absent="absent-"+UUID.randomUUID()+"@example.test";
        for(int i=0;i<10;i++)api.status(api.new Client().write("POST","/auth/login",Map.of("email",absent,"password","wrong")),401);
        var unknown=api.new Client().write("POST","/auth/login",Map.of("email",absent,"password","wrong"));api.status(unknown,429);
        assertThat(api.json(unknown).get("detail")).isEqualTo(api.json(blocked).get("detail"));
        assertThat(jdbc.sql("SELECT bucket_key FROM auth_request_limits").query(String.class).list()).noneMatch(key->key.contains(email)||key.contains(absent));
        clock.now=clock.now.plusSeconds(10);
        var repeated=api.new Client().write("POST","/auth/login",api.credentials(profile,OLD));api.status(repeated,429);
        assertThat(repeated.headers().firstValue("Retry-After")).contains("890");
        clock.now=clock.now.plusSeconds(890);api.status(api.login(profile,OLD).get("/auth/me"),200);
    }
    @Test void guessingCurrentPasswordIsLimitedWithoutLoggingOutOrAffectingOtherUsers()throws Exception {
        var admin=api.admin();var profile=api.create(admin,"USER");var other=api.create(admin,"USER");
        var a=api.login(profile,OLD);var b=api.login(other,OLD);
        for(int i=0;i<5;i++)api.status(a.write("POST","/auth/password",change("incorrect",NEW)),400);
        var blocked=a.write("POST","/auth/password",change(OLD,NEW));api.status(blocked,429);
        assertThat(blocked.headers().firstValue("Retry-After")).contains("900");api.status(a.get("/auth/me"),200);
        api.status(b.write("POST","/auth/password",change(OLD,NEW)),204);
        clock.now=clock.now.plusSeconds(900);api.status(a.write("POST","/auth/password",change(OLD,NEW)),204);
    }
    @Test void administratorsCanChangeTheirOwnPasswordWithoutChangingPermissions()throws Exception {
        var admin=api.admin();var profile=api.json(admin.get("/auth/me"));
        api.status(admin.write("POST","/auth/password",change(OLD,NEW)),204);
        var fresh=api.login(profile,NEW);var current=api.json(fresh.get("/auth/me"));
        assertThat(current.get("role").asText()).isEqualTo("ADMIN");assertThat(current.get("permissions")).isEqualTo(profile.get("permissions"));
        api.status(fresh.get("/admin/users"),200);
    }
    @Test void repeatRegistrationIsLimitedBeforeCreatingAdditionalAccounts()throws Exception {
        String email=api.email();var body=Map.of("email",email,"displayName","Giới hạn đăng ký","password",OLD);
        api.status(api.new Client().write("POST","/auth/register",body),201);
        api.status(api.new Client().write("POST","/auth/register",body),409);api.status(api.new Client().write("POST","/auth/register",body),409);
        var blocked=api.new Client().write("POST","/auth/register",body);api.status(blocked,429);
        assertThat(blocked.headers().firstValue("Retry-After")).contains("3600");
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE email=:email").param("email",email).query(Integer.class).single()).isEqualTo(1);
    }
    @Test void concurrentDatabaseReservationsRespectTheLimitAcrossLimiterInstances()throws Exception {
        var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<12;i++)results.add(pool.submit(()->{start.await();try{limits.take("concurrency-test",3,Duration.ofMinutes(1),clock.instant());return true;}catch(ResponseStatusException ex){assertThat(ex.getStatusCode().value()).isEqualTo(429);return false;}}));
            start.countDown();int accepted=0;for(var result:results)if(result.get(20,TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isEqualTo(3);
            assertThat(jdbc.sql("SELECT request_count FROM auth_request_limits WHERE bucket_key='concurrency-test'").query(Integer.class).single()).isEqualTo(3);
        }finally{pool.shutdownNow();}
    }
    @Test void changingEmailCannotBypassIpQuotaAndNewServiceKeepsStoredCounters() {
        var first=new AuthThrottle(limits,clock,1000,2,10,2);
        first.login("a@example.test","ip-a");first.login("b@example.test","ip-a");
        var restarted=new AuthThrottle(limits,clock,1000,2,10,2);
        assertThatThrownBy(()->restarted.login("c@example.test","ip-a")).isInstanceOf(ResponseStatusException.class);
        restarted.login("c@example.test","ip-b");
        first.register("a@example.test","ip-a");first.register("b@example.test","ip-a");
        assertThatThrownBy(()->restarted.register("c@example.test","ip-a")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void aGlobalBudgetAlsoBoundsRequestsFromDifferentAddresses() {
        var throttle=new AuthThrottle(limits,clock,2,100,10,100);
        throttle.login("a@example.test","ip-a");throttle.register("b@example.test","ip-b");
        assertThatThrownBy(()->throttle.login("c@example.test","ip-c")).isInstanceOf(ResponseStatusException.class);
        clock.now=clock.now.plusSeconds(60);throttle.login("c@example.test","ip-c");
    }
}
