package com.chineselearning.modules.settings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/admin/general-settings")
public class GeneralSettings {
 public record Settings(@Min(5) @Max(120) int idleMinutes, @Min(30) @Max(1440) int learnerMinutes,
   @Min(15) @Max(240) int managerMinutes, @Min(64) @Max(4096) int bodyLimitKiB, @NotNull @Min(0) Long version) {}
 private final JdbcClient jdbc;
 public GeneralSettings(JdbcClient jdbc) { this.jdbc=jdbc; }
 @GetMapping public Settings snapshot() {
   return jdbc.sql("SELECT idle_minutes,learner_minutes,manager_minutes,body_limit_ki_b,version FROM general_settings WHERE id=1").query(Settings.class).single();
 }
 @PutMapping @Transactional public Settings save(@Valid @RequestBody Settings input) {
   if(input.idleMinutes()>input.managerMinutes() || input.managerMinutes()>input.learnerMinutes())
     throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Thời gian không hoạt động ≤ phiên quản lý ≤ phiên học viên.");
   jdbc.sql("SELECT id FROM general_settings WHERE id=1 FOR UPDATE").query(Integer.class).single();
   var old=snapshot();
   if(!old.version().equals(input.version())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Cấu hình đã thay đổi. Hãy tải lại trước khi lưu.");
   jdbc.sql("UPDATE general_settings SET idle_minutes=:idle,learner_minutes=:learner,manager_minutes=:manager,body_limit_ki_b=:body,version=version+1 WHERE id=1")
     .param("idle",input.idleMinutes()).param("learner",input.learnerMinutes()).param("manager",input.managerMinutes()).param("body",input.bodyLimitKiB()).update();
   if(old.idleMinutes()!=input.idleMinutes() || old.learnerMinutes()!=input.learnerMinutes() || old.managerMinutes()!=input.managerMinutes())
     jdbc.sql("UPDATE account_sessions SET revoked=true WHERE NOT revoked").update();
   return snapshot();
 }
}
