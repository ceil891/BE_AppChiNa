package com.chineselearning;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class VocabularyApiTests {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    AdminUserApiTests api;
    AdminUserApiTests.Client learner;
    @BeforeEach void setup() throws Exception {
        api=new AdminUserApiTests();api.port=port;api.jdbc=jdbc;api.mapper=mapper;
        learner=api.login(api.createGraduate(api.admin()),AdminUserApiTests.PASSWORD);
    }
    @AfterEach void cleanup(){api.cleanup();}

    @Test void searchesHanziVietnameseAndPinyinWithOrWithoutTones() throws Exception {
        for (var query : new String[]{"你好", "nǐ hǎo", "NI HAO", "Xin chào"}) {
            var response = get("?q=" + encode(query));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json(response).get("items").get(0).get("hanzi").asText()).isEqualTo("你好");
        }
        assertThat(get("?q=xue").body()).contains("学生", "学校", "学习");
        assertThat(get("?q=" + encode("nǚ")).body()).contains("女儿");
        assertThat(get("?q=nv").body()).contains("女儿");
        assertThat(json(get("?q=%25")).get("total").asInt()).isZero();
        assertThat(json(get("?q=" + encode("' OR 1=1 --"))).get("total").asInt()).isZero();
        assertThat(json(get("?q=unknownword")).get("items").size()).isZero();
    }

    @Test void paginationAndLessonFilterStayConsistent() throws Exception {
        var first = json(get("?page=0&size=3"));
        var second = json(get("?page=1&size=3"));
        assertThat(first.get("total").asInt()).isEqualTo(120);
        assertThat(first.get("items").size()).isEqualTo(3);
        assertThat(second.get("items").size()).isEqualTo(3);
        assertThat(first.get("items").get(2).get("id").asLong()).isLessThan(second.get("items").get(0).get("id").asLong());
        assertThat(json(get("?lessonId=1")).get("total").asInt()).isEqualTo(10);
        var filtered = json(get("?lessonId=1&q=xue"));
        assertThat(filtered.get("total").asInt()).isEqualTo(1);
        assertThat(filtered.get("items").get(0).get("hanzi").asText()).isEqualTo("学生");
        assertThat(json(get("?page=100")).get("items").size()).isZero();
    }

    @Test void detailIncludesExamplesAndLessonLinksAndValidatesInput() throws Exception {
        var detail = json(get("/1"));
        assertThat(detail.get("word").get("exampleHanzi").asText()).isEqualTo("你好，我叫小明。");
        assertThat(detail.get("lessons").get(0).get("id").asLong()).isEqualTo(1);
        assertThat(get("/9999999").statusCode()).isEqualTo(404);
        assertThat(get("?lessonId=9999999").statusCode()).isEqualTo(404);
        for (var path : new String[]{"/bad", "?page=-1", "?size=0", "?size=51", "?lessonId=0", "?page=bad", "?q=" + "a".repeat(101)}) {
            var response = get(path);
            assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(400);
            assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/problem+json");
        }
    }

    @Test void editorialContentHasNoMissingFieldsOrDuplicateWordsOrOrphanLinks() {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM vocabulary").query(Long.class).single()).isEqualTo(120);
        assertThat(jdbc.sql("""
            SELECT COUNT(*) FROM vocabulary WHERE trim(hanzi)='' OR trim(pinyin)='' OR trim(meaning_vi)=''
            OR trim(example_hanzi)='' OR trim(example_pinyin)='' OR trim(example_meaning_vi)=''
            """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM (SELECT hanzi,pinyin FROM vocabulary GROUP BY hanzi,pinyin HAVING COUNT(*)>1) duplicates").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM vocabulary v WHERE NOT EXISTS (SELECT 1 FROM lesson_vocabulary lv WHERE lv.vocabulary_id=v.id)").query(Long.class).single()).isZero();
    }

    String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
    HttpResponse<String> get(String suffix) throws Exception {
        return learner.get("/vocabulary"+suffix);
    }
}
