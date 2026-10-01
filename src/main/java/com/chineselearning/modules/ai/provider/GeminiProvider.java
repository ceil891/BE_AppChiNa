package com.chineselearning.modules.ai.provider;

import com.chineselearning.modules.ai.config.AiSettings;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Component
public class GeminiProvider implements TutorProvider {
    private final AiSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String base;
    private final int countTimeout,generateTimeout;
    @org.springframework.beans.factory.annotation.Autowired
    public GeminiProvider(AiSettings settings,ObjectMapper mapper,
        @org.springframework.beans.factory.annotation.Value("${ai.count-timeout-seconds:10}") int countSeconds,
        @org.springframework.beans.factory.annotation.Value("${ai.generate-timeout-seconds:25}") int generateSeconds) {
        this(settings,mapper,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),
            "https://generativelanguage.googleapis.com/v1beta/models/",countSeconds,generateSeconds);
    }
    // Package-private transport injection is used only by local HTTP contract tests.
    GeminiProvider(AiSettings settings,ObjectMapper mapper,HttpClient client,String base) {
        this(settings,mapper,client,base,10,25);
    }
    GeminiProvider(AiSettings settings,ObjectMapper mapper,HttpClient client,String base,int countTimeout,int generateTimeout) {
        this.settings=settings;this.mapper=mapper;this.client=client;this.base=base;this.countTimeout=countTimeout;this.generateTimeout=generateTimeout;
    }
    @Override public Reply generate(String system,List<Message> history) {
        return generate(system,history,settings.snapshot().model());
    }
    @Override public Reply generate(String system,List<Message> history,String model) {
        if(!AiSettings.MODELS.contains(model)) throw new Failure("UNAVAILABLE");
        if(!settings.configured()) throw new Failure("UNAVAILABLE");
        var body=new LinkedHashMap<String,Object>();
        body.put("systemInstruction",Map.of("parts",List.of(Map.of("text",system))));
        body.put("contents",history.stream().map(m -> Map.of("role",m.role(),"parts",List.of(Map.of("text",m.text())))).toList());
        body.put("generationConfig",Map.of("maxOutputTokens",AiSettings.MAX_OUTPUT,"candidateCount",1,
            "thinkingConfig",model.startsWith("gemini-2.5-") ? Map.of("thinkingBudget",0)
                : Map.of("thinkingLevel",model.equals("gemini-3.8-flash") ? "LOW" : "MINIMAL")));
        var countBody=new LinkedHashMap<>(body);countBody.put("model","models/"+model);
        var count=post("countTokens",Map.of("generateContentRequest",countBody),countTimeout,model);
        int input=count.path("totalTokens").asInt(-1);
        if(input<0)throw new Failure("INVALID_RESPONSE");
        if(input>AiSettings.MAX_INPUT)throw new Failure("CONTEXT_LIMIT");
        var response=post("generateContent",body,generateTimeout,model);
        if(!response.path("promptFeedback").path("blockReason").asText("").isEmpty())throw new Failure("BLOCKED");
        var candidate=response.path("candidates").path(0);
        String finish=candidate.path("finishReason").asText("");
        if(!finish.equals("STOP"))throw new Failure(finish.equals("MAX_TOKENS")?"OUTPUT_LIMIT":finish.equals("SAFETY")?"BLOCKED":"INVALID_RESPONSE");
        var answer=new StringBuilder();
        for(var part:candidate.path("content").path("parts"))
            if(!part.path("thought").asBoolean(false) && part.has("text"))answer.append(part.get("text").asText());
        var text=answer.toString().strip();
        if(text.isBlank() || text.length()>8000)throw new Failure("INVALID_RESPONSE");
        var usage=response.path("usageMetadata");
        int in=usage.path("promptTokenCount").asInt(-1),out=usage.path("candidatesTokenCount").asInt(-1),total=usage.path("totalTokenCount").asInt(-1);
        if(in<0 || out<0 || total<in || total<out)throw new Failure("INVALID_RESPONSE");
        return new Reply(text,in,out,total);
    }
    private JsonNode post(String action,Object body,int seconds,String model) {
        try {
            var request=HttpRequest.newBuilder(URI.create(base+model+":"+action)).timeout(Duration.ofSeconds(seconds))
                .header("Content-Type","application/json").header("x-goog-api-key",settings.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var future=client.sendAsync(request,HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response;
            try { response=future.get(seconds,TimeUnit.SECONDS); }
            catch(TimeoutException ex) { future.cancel(true);throw new Failure("TIMEOUT"); }
            catch(InterruptedException ex) { future.cancel(true);Thread.currentThread().interrupt();throw new Failure("TIMEOUT"); }
            if(response.statusCode()==429)throw new Failure("RATE_LIMITED");
            if(response.statusCode()==401 || response.statusCode()==403 || response.statusCode()==404)throw new Failure("UNAVAILABLE");
            if(response.statusCode()<200 || response.statusCode()>=300)throw new Failure("PROVIDER_ERROR");
            if(response.body().length()>200000)throw new Failure("INVALID_RESPONSE");
            return mapper.readTree(response.body());
        } catch(Failure ex) { throw ex; }
        catch(ExecutionException ex) { throw new Failure(ex.getCause() instanceof HttpTimeoutException ? "TIMEOUT" : "PROVIDER_ERROR"); }
        catch(Exception ex) { throw new Failure("PROVIDER_ERROR"); }
    }
}
