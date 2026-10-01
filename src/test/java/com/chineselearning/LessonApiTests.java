package com.chineselearning;

import org.junit.jupiter.api.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import com.chineselearning.modules.lesson.service.LessonService;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LessonApiTests {
    @LocalServerPort int port;
    @Autowired LessonService lessons;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    AdminUserApiTests api;
    AdminUserApiTests.Client learner;
    @BeforeEach void setup() throws Exception {
        api=new AdminUserApiTests();api.port=port;api.jdbc=jdbc;api.mapper=mapper;
        learner=api.login(api.createGraduate(api.admin()),AdminUserApiTests.PASSWORD);
    }
    @AfterEach void cleanup(){api.cleanup();}

    @Test void migrationsProvideTwelveLessonsAndPreserveOriginalWords() {
        assertThat(lessons.list()).hasSize(12);
        assertThat(lessons.list().stream().mapToLong(l -> l.wordCount()).sum()).isEqualTo(120);
        for (var lesson : lessons.list()) assertThat(lessons.detail(lesson.id()).words()).hasSize(10);
        var first = lessons.detail(1);
        var second = lessons.detail(2);
        assertThat(first.words()).hasSize(10);
        assertThat(second.words()).hasSize(10);
        assertThat(first.words().get(0).hanzi()).isEqualTo("你好");
        assertThat(second.words().get(9).hanzi()).isEqualTo("米饭");
        assertThat(first.lesson().wordCount()).isEqualTo(first.words().size());
        assertThat(second.lesson().wordCount()).isEqualTo(second.words().size());
    }

    @Test void httpApiReturnsDatabaseDataAndNotFound() throws Exception {
        var list = request("/api/v1/lessons");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("Xin chào, mình là…", "Một ngày của bạn", "wordCount");
        var detail = request("/api/v1/lessons/1");
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("你好", "nǐ hǎo", "Xin chào");
        assertThat(request("/api/v1/lessons/999999").statusCode()).isEqualTo(404);
        assertThat(request("/api/v1/lessons/invalid").statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> request(String path) throws Exception {
        return learner.get(path.substring("/api/v1".length()));
    }
}
