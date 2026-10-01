package com.chineselearning.modules.progress.controller;

import com.chineselearning.modules.user.service.UserService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api/v1/roadmap")
public class RoadmapController {
    private final JdbcClient jdbc;
    private final UserService users;
    private final com.chineselearning.modules.foundation.FoundationService foundations;
    public RoadmapController(JdbcClient jdbc,UserService users,com.chineselearning.modules.foundation.FoundationService foundations) { this.jdbc=jdbc;this.users=users;this.foundations=foundations; }
    public record Step(String stage,String title,String path,String status) {}
    @GetMapping public List<Step> list(Principal principal) {
        UUID owner=principal==null?null:users.current(principal.getName()).id();
        boolean foundationComplete=foundations.completedCurriculum(owner);
        var result=new ArrayList<Step>();
        result.addAll(jdbc.sql("""
            SELECT CASE WHEN f.slug='ghep-am' THEN 'Lời chào' ELSE 'Pinyin' END AS stage,
            f.content->>'title' AS title,'/foundations/'||f.slug AS path,
            CASE WHEN EXISTS(SELECT 1 FROM foundation_lessons earlier
                WHERE earlier.published AND (earlier.position,earlier.slug)<(f.position,f.slug)
                AND NOT EXISTS(SELECT 1 FROM foundation_progress previous
                    WHERE previous.user_id=:owner AND previous.slug=earlier.slug AND previous.completed_at IS NOT NULL)) THEN 'LOCKED'
            WHEN p.completed_at IS NOT NULL THEN 'COMPLETED' WHEN p.user_id IS NOT NULL THEN 'STARTED' ELSE 'NEW' END AS status
            FROM foundation_lessons f LEFT JOIN foundation_progress p ON p.slug=f.slug AND p.user_id=:owner
            WHERE f.published ORDER BY f.position,f.slug
            """).param("owner",owner).query(Step.class).list());
        result.addAll(jdbc.sql("""
            SELECT 'Từ vựng chủ đề' AS stage,l.title,'/lessons/'||l.id AS path,
            CASE WHEN r.completed_at IS NOT NULL THEN 'COMPLETED' WHEN r.id IS NOT NULL THEN 'STARTED' ELSE 'NEW' END AS status
            FROM lessons l LEFT JOIN learning_records r ON r.lesson_id=l.id AND r.user_id=:owner
            WHERE l.published AND EXISTS(SELECT 1 FROM lesson_vocabulary v WHERE v.lesson_id=l.id) ORDER BY l.position,l.id
            """).param("owner",owner).query(Step.class).list());
        result.addAll(jdbc.sql("""
            SELECT '4 kỹ năng' AS stage,e.content->>'title' AS title,
            COALESCE((SELECT '/skills/attempts/'||a.id FROM skill_attempts a
                WHERE a.user_id=:owner AND a.exercise_id=e.id AND a.submitted_at IS NULL
                ORDER BY a.created_at DESC,a.id DESC LIMIT 1),
                '/skills?skill='||e.skill||'&exercise='||e.id) AS path,
            CASE WHEN EXISTS(SELECT 1 FROM skill_attempts a WHERE a.user_id=:owner AND a.exercise_id=e.id AND a.submitted_at IS NOT NULL) THEN 'COMPLETED'
            WHEN EXISTS(SELECT 1 FROM skill_attempts a WHERE a.user_id=:owner AND a.exercise_id=e.id) THEN 'STARTED' ELSE 'NEW' END AS status
            FROM skill_exercises e WHERE e.published
            ORDER BY CASE e.skill WHEN 'LISTENING' THEN 1 WHEN 'SPEAKING' THEN 2 WHEN 'READING' THEN 3 ELSE 4 END,e.id
            """).param("owner",owner).query(Step.class).list());
        return result.stream().map(step -> !foundationComplete && !step.path().startsWith("/foundations/")
            ?new Step(step.stage(),step.title(),step.path(),"LOCKED"):step).toList();
    }
}
