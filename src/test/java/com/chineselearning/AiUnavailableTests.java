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

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="ai.gemini-api-key=")
@ActiveProfiles("test")
class AiUnavailableTests {
    @LocalServerPort int port; @Autowired JdbcClient jdbc; @Autowired ObjectMapper mapper;
    @Test void missingKeyFailsClearlyWithoutChargingOrBreakingLearning()throws Exception {
        var api=new AdminUserApiTests();api.port=port;api.jdbc=jdbc;api.mapper=mapper;var helpers=new QuizApiTests();helpers.api=api;
        try {
            api.status(api.new Client().get("/ai/status"),401);var c=helpers.learner();
            assertThat(api.json(c.get("/ai/status")).get("available").asBoolean()).isFalse();
            assertThat(api.json(c.get("/ai/status")).get("reason").asText()).isEqualTo("NOT_CONFIGURED");
            var d=api.json(c.write("POST","/ai/conversations",Map.of("requestId",UUID.randomUUID())));
            String path="/ai/conversations/"+d.get("conversation").get("id").asText();
            api.status(c.write("POST",path+"/turns",Map.of("requestId",UUID.randomUUID(),"question","你好")),503);
            assertThat(api.json(c.get(path)).get("turns").size()).isZero();assertThat(api.json(c.get("/ai/status")).get("usedToday").asInt()).isZero();
            api.status(c.get("/lessons"),200);api.status(c.get("/progress"),200);
        }finally{api.cleanup();}
    }
}
