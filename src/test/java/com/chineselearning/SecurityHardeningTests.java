package com.chineselearning;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="security.cors.allowed-origins=https://learning.example")
@ActiveProfiles("test")
class SecurityHardeningTests {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc; @Autowired ObjectMapper mapper;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    AdminUserApiTests api;
    @BeforeEach void setup() { api=new AdminUserApiTests();api.port=port;api.jdbc=jdbc;api.mapper=mapper; }
    @AfterEach void cleanup() { api.cleanup(); }
    HttpResponse<String> request(String method,String path,String body,String type,String origin,boolean chunked) throws Exception {
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path));
        if(type!=null)r.header("Content-Type",type);
        if(origin!=null)r.header("Origin",origin);
        if(method.equals("OPTIONS"))r.header("Access-Control-Request-Method","PUT").header("Access-Control-Request-Headers","X-CSRF-TOKEN,Content-Type");
        var publisher=body==null?HttpRequest.BodyPublishers.noBody():chunked?HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))):HttpRequest.BodyPublishers.ofString(body);
        return HttpClient.newHttpClient().send(r.method(method,publisher).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void generalSettingsValidateVersionLimitsAndRevokeSessions() throws Exception {
        var admin=api.admin();
        var original=mapper.readTree(admin.get("/admin/general-settings").body());
        long version=original.get("version").asLong();
        try {
            assertThat(admin.write("PUT","/admin/general-settings",Map.of("idleMinutes",121,"learnerMinutes",480,"managerMinutes",120,"bodyLimitKiB",1024,"version",version)).statusCode()).isEqualTo(400);
            assertThat(admin.write("PUT","/admin/general-settings",Map.of("idleMinutes",30,"learnerMinutes",480,"managerMinutes",15,"bodyLimitKiB",1024,"version",version)).statusCode()).isEqualTo(400);
            assertThat(admin.write("PUT","/admin/general-settings",Map.of("idleMinutes",30,"learnerMinutes",480,"managerMinutes",120,"bodyLimitKiB",64,"version",version)).statusCode()).isEqualTo(200);
            assertThat(request("POST","/auth/login","x".repeat(65537),"application/json",null,true).statusCode()).isEqualTo(413);
            assertThat(admin.write("PUT","/admin/general-settings",Map.of("idleMinutes",30,"learnerMinutes",480,"managerMinutes",120,"bodyLimitKiB",1024,"version",version)).statusCode()).isEqualTo(409);
            assertThat(admin.write("PUT","/admin/general-settings",Map.of("idleMinutes",40,"learnerMinutes",480,"managerMinutes",120,"bodyLimitKiB",1024,"version",version+1)).statusCode()).isEqualTo(200);
            assertThat(admin.get("/auth/me").statusCode()).isEqualTo(401);
        } finally {
            jdbc.sql("UPDATE general_settings SET idle_minutes=30,learner_minutes=480,manager_minutes=120,body_limit_ki_b=1024,version=version+1 WHERE id=1").update();
        }
    }
    @Test void headersCorsBodyLimitsAndUploadFailClosed() throws Exception {
        var response=request("GET","/lessons",null,null,null,false);
        assertThat(response.headers().firstValue("Content-Security-Policy").orElse("")).contains("frame-ancestors 'none'","script-src 'self'");
        assertThat(response.headers().firstValue("Referrer-Policy")).contains("strict-origin-when-cross-origin");
        assertThat(response.headers().firstValue("Permissions-Policy").orElse("")).contains("microphone=(self)");
        assertThat(response.headers().firstValue("X-Request-ID").orElse("")).matches("[0-9a-f-]{36}");
        assertThat(request("OPTIONS","/profile",null,null,"https://learning.example",false).headers().firstValue("Access-Control-Allow-Origin")).contains("https://learning.example");
        for(String origin:List.of("https://evil.example","null","https://learning.example.evil.test")) {
            var denied=request("OPTIONS","/profile",null,null,origin,false);
            assertThat(denied.statusCode()).isEqualTo(403);assertThat(denied.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
            assertThat(request("GET","/lessons",null,null,origin,false).statusCode()).isEqualTo(403);
        }
        assertThat(request("POST","/auth/login","fake","multipart/form-data; boundary=test",null,false).statusCode()).isEqualTo(415);
        assertThat(request("POST","/auth/login","fake","text/plain",null,false).statusCode()).isEqualTo(415);
        for(boolean chunked:List.of(false,true)) {
            var big=request("POST","/auth/login","x".repeat(1_048_577),"application/json",null,chunked);
            assertThat(big.statusCode()).isEqualTo(413);assertThat(big.body()).doesNotContain("Exception","stackTrace");
        }
    }
    @Test void everyMappedAdminEndpointRejectsOrdinaryUsers() throws Exception {
        var admin=api.admin();var user=api.login(api.createGraduate(admin),AdminUserApiTests.PASSWORD);
        int checked=0;
        for(var mapping:mappings.getHandlerMethods().keySet()) for(String pattern:mapping.getPatternValues()) {
            if(!pattern.startsWith("/api/v1/admin/"))continue;
            String path=pattern.substring("/api/v1".length()).replaceAll("\\{[^}]+}","1");
            for(var method:mapping.getMethodsCondition().getMethods()) {
                var response=method.name().equals("GET")?user.get(path):user.write(method.name(),path,Map.of());
                assertThat(response.statusCode()).as(method+" "+path).isEqualTo(403);checked++;
            }
        }
        assertThat(checked).isGreaterThan(50);
    }
    @Test void expiredIdleAndAbsoluteSessionsCannotBeRevived() throws Exception {
        var admin=api.admin();var account=api.createGraduate(admin);
        var learner=api.login(account,AdminUserApiTests.PASSWORD);
        String owner=account.get("id").asText();
        jdbc.sql("UPDATE account_sessions SET last_seen_at=CURRENT_TIMESTAMP-INTERVAL '31 minutes' WHERE user_id=:owner").param("owner",UUID.fromString(owner)).update();
        assertThat(learner.get("/auth/me").statusCode()).isEqualTo(401);
        learner=api.login(account,AdminUserApiTests.PASSWORD);
        jdbc.sql("UPDATE account_sessions SET created_at=CURRENT_TIMESTAMP-INTERVAL '9 hours',last_seen_at=CURRENT_TIMESTAMP WHERE user_id=:owner").param("owner",UUID.fromString(owner)).update();
        assertThat(learner.get("/auth/me").statusCode()).isEqualTo(401);
        String adminId=api.json(admin.get("/auth/me")).get("id").asText();
        jdbc.sql("UPDATE account_sessions SET created_at=CURRENT_TIMESTAMP-INTERVAL '3 hours',last_seen_at=CURRENT_TIMESTAMP WHERE user_id=:owner").param("owner",UUID.fromString(adminId)).update();
        assertThat(admin.get("/admin/users").statusCode()).isEqualTo(401);
    }
    @Test void malformedInputAndSqlPayloadsDoNotLeakOrEscalate() throws Exception {
        var admin=api.admin();var account=api.createGraduate(admin);var user=api.login(account,AdminUserApiTests.PASSWORD);
        var response=user.write("PATCH","/profile",Map.of("displayName","Changed","role","ADMIN"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(api.json(user.get("/auth/me")).get("role").asText()).isEqualTo("USER");
        var badId=admin.get("/admin/users/not-a-uuid");
        assertThat(badId.statusCode()).isEqualTo(400);assertThat(badId.body()).doesNotContain("java.","UUID","not-a-uuid\" is", "stackTrace");
        var query=URLEncoder.encode("' OR 1=1; DROP TABLE users; --",java.nio.charset.StandardCharsets.UTF_8);
        assertThat(api.json(admin.get("/admin/users?q="+query)).get("total").asLong()).isZero();
        assertThat(api.json(user.get("/vocabulary?q="+query)).get("total").asLong()).isZero();
        assertThat(user.get("/profile").statusCode()).isEqualTo(200);
        assertThat(admin.write("POST","/admin/courses",Map.of("title"," ","description","","lessonIds",List.of(-1),"version",0)).statusCode()).isEqualTo(400);
        assertThat(admin.write("POST","/admin/courses",Map.of("title","Missing version","description","","lessonIds",List.of())).statusCode()).isEqualTo(400);
        assertThat(admin.write("PUT","/admin/ai-settings",Map.of("enabled",true,"model","gemini-2.5-flash","dailyTurns",101,"globalDailyTurns",1,"version",0)).statusCode()).isEqualTo(400);
    }
}
