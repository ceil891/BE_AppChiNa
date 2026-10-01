package com.chineselearning.modules.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AiSettings {
    public static final java.util.Set<String> MODELS=java.util.Set.of("gemini-2.5-flash","gemini-2.5-flash-lite","gemini-3.5-flash-lite","gemini-3.8-flash");
    public record Runtime(boolean enabled,String model,int dailyTurns,int globalDailyTurns,long version) {}
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @org.springframework.beans.factory.annotation.Autowired
    public void setJdbc(org.springframework.jdbc.core.simple.JdbcClient jdbc) { this.jdbc=jdbc; }
    public Runtime snapshot() {
        if(jdbc==null) return new Runtime(true,model,dailyTurns,globalDailyTurns,0);
        return jdbc.sql("SELECT enabled,model,daily_turns,global_daily_turns,version FROM ai_runtime_settings WHERE id=1")
            .query(Runtime.class).optional().orElse(new Runtime(true,model,dailyTurns,globalDailyTurns,0));
    }
    public static final int MAX_INPUT=8192, MAX_OUTPUT=1024, RESERVATION=MAX_INPUT+MAX_OUTPUT;
    public static final int MAX_TURNS=20, MAX_CONVERSATIONS=50;
    public final String apiKey, model;
    public final int dailyTurns, globalDailyTurns;
    public AiSettings(@Value("${ai.gemini-api-key:}") String apiKey,
            @Value("${ai.gemini-model:gemini-3.5-flash-lite}") String model,
            @Value("${ai.daily-turns:20}") int dailyTurns,
            @Value("${ai.global-daily-turns:200}") int globalDailyTurns) {
        this.apiKey=apiKey.strip(); this.model=model; this.dailyTurns=dailyTurns; this.globalDailyTurns=globalDailyTurns;
        if (!java.util.Set.of("gemini-2.5-flash","gemini-2.5-flash-lite","gemini-3.5-flash-lite","gemini-3.8-flash").contains(model) || dailyTurns<1 || dailyTurns>100 || globalDailyTurns<1 || globalDailyTurns>10000)
            throw new IllegalArgumentException("Invalid AI configuration: use a supported Gemini Flash model and positive bounded quotas.");
    }
    public boolean configured() { return !apiKey.isBlank(); }
}
