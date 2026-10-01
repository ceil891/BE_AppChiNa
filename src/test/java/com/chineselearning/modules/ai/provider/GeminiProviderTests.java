package com.chineselearning.modules.ai.provider;

import com.chineselearning.modules.ai.config.AiSettings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import tools.jackson.databind.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class GeminiProviderTests {
    HttpServer server; ObjectMapper mapper=new ObjectMapper(); GeminiProvider provider;
    int status=200;String response;int tokens=80;volatile boolean delay;final AtomicInteger calls=new AtomicInteger();JsonNode generation;String key;
    @BeforeEach void setup()throws Exception {
        response="""
            {"candidates":[{"finishReason":"STOP","content":{"parts":[{"thought":true,"text":"internal"},{"text":"你好\\nnǐ hǎo\\nXin chào"}]}}],"usageMetadata":{"promptTokenCount":80,"candidatesTokenCount":20,"totalTokenCount":100}}
            """;
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            calls.incrementAndGet();key=exchange.getRequestHeaders().getFirst("x-goog-api-key");
            if(delay)try { Thread.sleep(1500); }catch(InterruptedException ex){Thread.currentThread().interrupt();}
            assertThat(exchange.getRequestURI().getQuery()).isNull();
            var body=mapper.readTree(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            boolean count=exchange.getRequestURI().getPath().endsWith(":countTokens");
            if(count) {assertThat(body.get("generateContentRequest").get("systemInstruction").toString()).contains("Gia sư");}
            else generation=body;
            byte[] bytes=(count?"{\"totalTokens\":"+tokens+"}":response).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(count?200:status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        provider=new GeminiProvider(new AiSettings("test-key-only","gemini-2.5-flash",20,200),mapper,HttpClient.newHttpClient(),"http://127.0.0.1:"+server.getAddress().getPort()+"/");
    }
    @AfterEach void close(){server.stop(0);}
    TutorProvider.Reply generate(){return provider.generate("Gia sư",List.of(new TutorProvider.Message("user","你好")));}
    @Test void requestContractUsesHeaderSystemInstructionTokenCountAndBoundedOutput() {
        var reply=generate();assertThat(reply.text()).contains("nǐ hǎo").doesNotContain("internal");assertThat(reply.totalTokens()).isEqualTo(100);
        assertThat(calls.get()).isEqualTo(2);assertThat(key).isEqualTo("test-key-only");
        assertThat(generation.get("generationConfig").get("maxOutputTokens").asInt()).isEqualTo(1024);
        assertThat(generation.get("generationConfig").get("thinkingConfig").get("thinkingBudget").asInt()).isZero();
        assertThat(generation.has("tools")).isFalse();assertThat(generation.get("contents").get(0).get("role").asText()).isEqualTo("user");
    }
    @Test void modernFlashModelsUseSupportedThinkingLevelsInsteadOfLegacyBudgets() {
        for(String model:List.of("gemini-3.5-flash-lite","gemini-3.8-flash")) {
            provider=new GeminiProvider(new AiSettings("test-key-only",model,20,200),mapper,HttpClient.newHttpClient(),"http://127.0.0.1:"+server.getAddress().getPort()+"/");
            assertThat(generate().text()).contains("你好");
            var thinking=generation.get("generationConfig").get("thinkingConfig");
            assertThat(thinking.has("thinkingBudget")).isFalse();
            assertThat(thinking.get("thinkingLevel").asText()).isEqualTo(model.equals("gemini-3.8-flash")?"LOW":"MINIMAL");
        }
        assertThatThrownBy(()->new AiSettings("test","../other?key=oops",20,200)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void providerErrorsAndSafetyBlocksNeverBecomeFabricatedAnswersOrLeakResponseBody() {
        for(int code:List.of(400,401,403,404,429,500)) {
            status=code;response="secret-provider-debug";
            assertThatThrownBy(this::generate).isInstanceOf(TutorProvider.Failure.class).hasMessage(code==429?"RATE_LIMITED":List.of(401,403,404).contains(code)?"UNAVAILABLE":"PROVIDER_ERROR");
        }
        status=200;response="{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}";
        assertThatThrownBy(this::generate).hasMessage("BLOCKED");
        response="{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}]}";assertThatThrownBy(this::generate).hasMessage("OUTPUT_LIMIT");
        response="{\"candidates\":[]}";assertThatThrownBy(this::generate).hasMessage("INVALID_RESPONSE");
        response="not-json";assertThatThrownBy(this::generate).hasMessage("PROVIDER_ERROR");
        assertThat(calls.get()).isEqualTo(20);
    }
    @Test void inputOverBudgetStopsBeforeGeneration() {
        tokens=8193;assertThatThrownBy(this::generate).hasMessage("CONTEXT_LIMIT");assertThat(calls.get()).isEqualTo(1);
    }
    @Test void slowProviderTimesOutWithoutRetryOrGeneration() {
        delay=true;
        provider=new GeminiProvider(new AiSettings("test-key-only","gemini-2.5-flash",20,200),mapper,HttpClient.newHttpClient(),"http://127.0.0.1:"+server.getAddress().getPort()+"/",1,1);
        assertThatThrownBy(this::generate).hasMessage("TIMEOUT");assertThat(calls.get()).isEqualTo(1);
    }
}
