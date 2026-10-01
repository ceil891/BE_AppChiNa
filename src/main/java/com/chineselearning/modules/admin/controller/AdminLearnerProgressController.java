package com.chineselearning.modules.admin.controller;

import com.chineselearning.common.security.PermissionService;
import com.chineselearning.modules.progress.repository.LearningEventRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import java.security.Principal;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/learner-progress")
public class AdminLearnerProgressController {
    private final JdbcClient jdbc;private final PermissionService permissions;private final LearningEventRepository events;private final Clock clock;
    public AdminLearnerProgressController(JdbcClient jdbc,PermissionService permissions,LearningEventRepository events,Clock clock) { this.jdbc=jdbc;this.permissions=permissions;this.events=events;this.clock=clock; }
    private void require(Principal p) { permissions.require(p.getName(),"users.read");permissions.require(p.getName(),"users.progress.read"); }
    private static final String LEARNER="NOT EXISTS(SELECT 1 FROM role_permissions rp WHERE rp.role_code=u.role AND rp.permission_code IN ('users.read','roles.read','vocabulary.read','lessons.read','quizzes.read','skills.read','foundations.read'))";
    public record Learner(UUID id,String displayName,String email,OffsetDateTime lastStudiedAt,long foundations,long lessons,long activities) {}
    public record Page(List<Learner> items,long total,int page,int size) {}
    @GetMapping @Transactional(readOnly=true) public Page list(Principal p,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int inactiveDays,@RequestParam(defaultValue="0") int page) {
        require(p);if(q.length()>100 || inactiveDays<0 || inactiveDays>3650 || page<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Bộ lọc không hợp lệ.");
        String filter=" FROM users u WHERE "+LEARNER+" AND (POSITION(LOWER(:q) IN LOWER(u.display_name))>0 OR POSITION(LOWER(:q) IN LOWER(u.email))>0) AND (:days=0 OR NOT EXISTS(SELECT 1 FROM learning_events e WHERE e.user_id=u.id AND e.occurred_at>=:since))";
        var since=OffsetDateTime.now(clock).minusDays(inactiveDays);
        var items=jdbc.sql("""
            SELECT u.id,u.display_name,u.email,(SELECT MAX(occurred_at) FROM learning_events WHERE user_id=u.id) AS last_studied_at,
            (SELECT COUNT(*) FROM foundation_progress WHERE user_id=u.id AND completed_at IS NOT NULL) AS foundations,
            (SELECT COUNT(*) FROM learning_records WHERE user_id=u.id AND completed_at IS NOT NULL) AS lessons,
            (SELECT COUNT(*) FROM learning_events WHERE user_id=u.id) AS activities
            """+filter+" ORDER BY last_studied_at ASC NULLS FIRST,u.id LIMIT 20 OFFSET :offset")
            .param("q",q.strip()).param("days",inactiveDays).param("since",since).param("offset",(long)page*20).query(Learner.class).list();
        long count=jdbc.sql("SELECT COUNT(*)"+filter).param("q",q.strip()).param("days",inactiveDays).param("since",since).query(Long.class).single();
        return new Page(items,count,page,20);
    }
    @GetMapping("/{id}") @Transactional(readOnly=true) public Map<String,Object> detail(Principal p,@PathVariable UUID id) {
        require(p);if(!jdbc.sql("SELECT EXISTS(SELECT 1 FROM users u WHERE id=:id AND "+LEARNER+")").param("id",id).query(Boolean.class).single())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy học viên.");
        return Map.of("activities",events.list(id,"","",0,50,OffsetDateTime.now(clock)),"activityCount",events.count(id,"","",OffsetDateTime.now(clock)),
            "foundations",jdbc.sql("SELECT p.slug,f.content->>'title' AS title,p.best_score,p.last_score,p.attempts,p.completed_at FROM foundation_progress p JOIN foundation_lessons f ON f.slug=p.slug WHERE p.user_id=:id ORDER BY f.position").param("id",id).query().listOfRows(),
            "lessons",jdbc.sql("SELECT l.title,r.completed_at FROM learning_records r JOIN lessons l ON l.id=r.lesson_id WHERE r.user_id=:id ORDER BY r.updated_at DESC").param("id",id).query().listOfRows(),
            "review",jdbc.sql("SELECT label,score,occurred_at FROM learning_events WHERE user_id=:id AND score<70 ORDER BY occurred_at DESC LIMIT 20").param("id",id).query().listOfRows());
    }
}
