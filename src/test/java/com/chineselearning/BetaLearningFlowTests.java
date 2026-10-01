package com.chineselearning;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"beta","test"})
class BetaLearningFlowTests {
    @LocalServerPort int port; @Autowired JdbcClient jdbc; @Autowired ObjectMapper mapper;
    @Test void packagedSkillAudioIsPublicAndSupportsPlaybackRanges() throws Exception {
        Assumptions.assumeTrue(new org.springframework.core.io.ClassPathResource("static/audio/skills/listening-greetings-v1-1.mp3").exists(), "Run with -Pbeta after building frontend to verify packaged audio.");
        var client=java.net.http.HttpClient.newHttpClient();
        var uri=java.net.URI.create("http://127.0.0.1:"+port+"/audio/skills/listening-greetings-v1-1.mp3");
        var full=client.send(java.net.http.HttpRequest.newBuilder(uri).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        assertThat(full.statusCode()).isEqualTo(200);assertThat(full.body().length).isGreaterThan(1000);
        var partial=client.send(java.net.http.HttpRequest.newBuilder(uri).header("Range","bytes=0-99").GET().build(),java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        assertThat(partial.statusCode()).isEqualTo(206);assertThat(partial.body()).hasSize(100);
    }
    @Test void completeLearningSessionKeepsPrivateDataAcrossLoginAndUsesSecureCookies()throws Exception {
        var api=new AdminUserApiTests();api.port=port;api.jdbc=jdbc;api.mapper=mapper;
        var quiz=new QuizApiTests();quiz.api=api;quiz.jdbc=jdbc;quiz.mapper=mapper;
        try {
            var csrf=api.new Client().get("/auth/csrf");
            assertThat(csrf.headers().allValues("set-cookie").toString()).contains("Secure","HttpOnly","SameSite=Lax");
            var admin=api.admin();var profile=api.create(admin,"USER");var second=api.create(admin,"USER");
            var a=api.login(profile,AdminUserApiTests.PASSWORD);var b=api.login(second,AdminUserApiTests.PASSWORD);
            var lesson=api.json(a.get("/lessons/1"));
            var record=api.json(a.write("POST","/learning-records",Map.of("lessonId",1,"note","Ghi chú riêng cho phiên beta")));
            api.status(a.write("POST","/learning-records/complete",Map.of("lessonId",1)),200);
            var card=api.json(a.write("POST","/flashcards",Map.of("vocabularyId",lesson.get("words").get(0).get("id").asLong())));
            var review=Map.of("requestId",UUID.randomUUID(),"rating","REMEMBER","version",card.get("version").asLong());
            String cardPath="/flashcards/"+card.get("id").asText();api.status(a.write("POST",cardPath+"/reviews",review),200);
            var attempt=quiz.start(a,5);attempt=quiz.fill(a,attempt,4);var result=quiz.submit(a,attempt);
            assertThat(result.get("attempt").get("score").asInt()).isEqualTo(80);
            var progress=api.json(a.get("/progress"));
            assertThat(progress.get("totals").get("completedLessons").asInt()).isEqualTo(1);
            assertThat(progress.get("totals").get("flashcardReviews").asInt()).isEqualTo(1);
            assertThat(progress.get("totals").get("submittedQuizzes").asInt()).isEqualTo(1);
            assertThat(progress.get("streak").get("activeDays").asInt()).isEqualTo(1);
            api.status(b.get("/learning-records/"+record.get("id").asText()),404);api.status(b.get(cardPath),404);api.status(b.get(quiz.path(result)),404);
            assertThat(api.json(b.get("/progress/events")).get("total").asInt()).isZero();
            api.status(a.write("POST","/auth/logout",null),204);api.status(a.get("/progress"),401);
            var resumed=api.login(profile,AdminUserApiTests.PASSWORD);
            assertThat(api.json(resumed.get("/learning-records")).get(0).get("note").asText()).isEqualTo("Ghi chú riêng cho phiên beta");
            assertThat(api.json(resumed.get("/progress")).get("totals")).isEqualTo(progress.get("totals"));
            api.status(resumed.write("POST",cardPath+"/reviews",review),200);quiz.submit(resumed,result);
            assertThat(api.json(resumed.get("/progress/events")).get("total").asInt()).isEqualTo(3);
        }finally{api.cleanup();}
    }
}
