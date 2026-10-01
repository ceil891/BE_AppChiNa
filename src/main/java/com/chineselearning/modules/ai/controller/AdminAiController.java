package com.chineselearning.modules.ai.controller;
import com.chineselearning.modules.ai.config.AiSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/ai-settings")
public class AdminAiController {
    public record Input(@NotNull Boolean enabled,@NotBlank @Size(max=80) String model,@Min(1) @Max(100) int dailyTurns,
        @Min(1) @Max(10000) int globalDailyTurns,@NotNull @Min(0) Long version) {}
    public record View(AiSettings.Runtime settings,boolean keyConfigured,List<String> models) {}
    private final AiSettings settings; private final JdbcClient jdbc;
    public AdminAiController(AiSettings settings,JdbcClient jdbc) { this.settings=settings;this.jdbc=jdbc; }
    @GetMapping public View get() { return new View(settings.snapshot(),settings.configured(),AiSettings.MODELS.stream().sorted().toList()); }
    @PutMapping @Transactional public View save(@Valid @RequestBody Input input) {
        if(!AiSettings.MODELS.contains(input.model())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Model không được hỗ trợ.");
        // Shared database lock also covers first insertion and multiple server instances.
        jdbc.sql("SELECT id FROM authorization_lock WHERE id=1 FOR UPDATE").query(Integer.class).single();
        var current=settings.snapshot();
        if(current.version()!=input.version()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Cấu hình đã thay đổi. Hãy tải lại trước khi lưu.");
        jdbc.sql("""
            INSERT INTO ai_runtime_settings(id,enabled,model,daily_turns,global_daily_turns,version)
            VALUES (1,:enabled,:model,:daily,:global,:version)
            ON CONFLICT(id) DO UPDATE SET enabled=EXCLUDED.enabled,model=EXCLUDED.model,
            daily_turns=EXCLUDED.daily_turns,global_daily_turns=EXCLUDED.global_daily_turns,version=EXCLUDED.version
            """).param("enabled",input.enabled()).param("model",input.model()).param("daily",input.dailyTurns())
            .param("global",input.globalDailyTurns()).param("version",current.version()+1).update();
        return get();
    }
}
