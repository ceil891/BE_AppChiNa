package com.chineselearning.modules.skills;

import com.chineselearning.modules.skills.SkillCatalog.*;
import com.chineselearning.modules.user.service.UserService;
import com.chineselearning.modules.progress.service.LearningEventService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.*;
import java.util.*;

@Service
public class SkillService {
    public record Start(@NotBlank @Size(max=60) String exerciseId,@NotNull UUID requestId) {}
    public record Answer(@NotBlank String questionId,@NotBlank @Size(max=500) String value) {}
    public record Submit(@NotNull @Size(min=1,max=20) List<@NotNull @Valid Answer> answers) {}
    public record Draft(@Min(0) long version,@NotNull @Size(max=20) List<@NotNull @Valid Answer> answers) {}
    public record Card(String id,Skill skill,String title,String description,int questionCount) {}
    public record Stat(Skill skill,long attempts,long completedExercises,BigDecimal averageScore,Integer bestScore,OffsetDateTime lastPracticedAt) {}
    public record Summary(UUID id,String exerciseId,Skill skill,String title,Integer score,OffsetDateTime createdAt,OffsetDateTime submittedAt) {}
    public record Page(List<Summary> items,long total,int page,int size) {}
    public record QuestionView(String id,String prompt,String text,String hint,List<String> options,String answer,Boolean correct,List<String> accepted,String explanation,String audioSrc) {}
    public record Attempt(Summary summary,String description,List<QuestionView> questions,long draftVersion) {}
    private record Stored(Summary summary,Exercise exercise,List<Answer> answers,long draftVersion) {}
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final SkillCatalog catalog;
    private final UserService users;
    private final Clock clock;
    private final LearningEventService events;
    public SkillService(JdbcClient jdbc,ObjectMapper mapper,SkillCatalog catalog,UserService users,Clock clock,LearningEventService events) {
        this.jdbc=jdbc;this.mapper=mapper;this.catalog=catalog;this.users=users;this.clock=clock;this.events=events;
    }
    public List<Card> exercises() { return catalog.all().stream().map(e->new Card(e.id(),e.skill(),e.title(),e.description(),e.questions().size())).toList(); }
    private UUID owner(String email) { return users.current(email).id(); }
    private static final String SUMMARY="id,exercise_id,skill,title,score,created_at,submitted_at";
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Stat> stats(String email) {
        return statsFor(owner(email));
    }
    private List<Stat> statsFor(UUID owner) {
        return Arrays.stream(Skill.values()).map(skill->jdbc.sql("""
            SELECT :skill AS skill,count(*) AS attempts,count(DISTINCT exercise_id) AS completed_exercises,
                round(avg(score),1) AS average_score,max(score) AS best_score,max(submitted_at) AS last_practiced_at
            FROM skill_attempts WHERE user_id=:owner AND skill=:skill AND submitted_at IS NOT NULL
            """).param("skill",skill.name()).param("owner",owner).query(Stat.class).single()).toList();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page history(String email,String skill,int page,int size) {
        return historyFor(owner(email),skill,page,size);
    }
    private Page historyFor(UUID owner,String skill,int page,int size) {
        if(page<0 || size<1 || size>50 || (!skill.isEmpty() && Arrays.stream(Skill.values()).noneMatch(s->s.name().equals(skill))))throw bad("Bộ lọc không hợp lệ.");
        String filter=" FROM skill_attempts WHERE user_id=:owner AND (:skill='' OR skill=:skill)";
        var items=jdbc.sql("SELECT "+SUMMARY+filter+" ORDER BY created_at DESC,id DESC LIMIT :size OFFSET :offset")
            .param("owner",owner).param("skill",skill).param("size",size).param("offset",(long)page*size).query(Summary.class).list();
        long total=jdbc.sql("SELECT count(*)"+filter).param("owner",owner).param("skill",skill).query(Long.class).single();
        return new Page(items,total,page,size);
    }
    private void requireLearnerRead(String actor,UUID learner) {
        var permissions=users.current(actor).permissions();
        if(!permissions.contains("users.read") || !permissions.contains("users.skills.read"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Bạn không có quyền xem kỹ năng học viên.");
        if(!jdbc.sql("SELECT EXISTS(SELECT 1 FROM users WHERE id=:id)").param("id",learner).query(Boolean.class).single())throw missing();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Stat> learnerStats(String actor,UUID learner) {
        requireLearnerRead(actor,learner);return statsFor(learner);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page learnerHistory(String actor,UUID learner,String skill,int page,int size) {
        requireLearnerRead(actor,learner);return historyFor(learner,skill,page,size);
    }
    @Transactional(readOnly=true)
    public Attempt learnerAttempt(String actor,UUID learner,UUID attempt) {
        requireLearnerRead(actor,learner);return view(stored(learner,attempt));
    }
    @Transactional
    public Attempt start(String email,Start input) {
        var owner=owner(email);
        var previous=jdbc.sql("SELECT id FROM skill_attempts WHERE user_id=:owner AND request_id=:request")
            .param("owner",owner).param("request",input.requestId()).query(UUID.class).optional();
        if(previous.isPresent()) {
            var existing=stored(owner,previous.get());
            if(!existing.summary().exerciseId().equals(input.exerciseId()))throw conflict("Mã tạo lượt đã dùng cho bài khác.");
            return view(existing);
        }
        var exercise=catalog.find(input.exerciseId()).orElseThrow(this::missing);
        var questions=exercise.questions().stream().map(q->{var options=new ArrayList<>(q.options());Collections.shuffle(options);
            return new Question(q.id(),q.prompt(),q.text(),q.hint(),options,q.accepted(),q.explanation(),q.audioSrc());}).toList();
        var snapshot=new Exercise(exercise.id(),exercise.skill(),exercise.title(),exercise.description(),questions);
        jdbc.sql("""
            INSERT INTO skill_attempts(id,user_id,request_id,exercise_id,skill,title,snapshot,created_at)
            VALUES(:id,:owner,:request,:exercise,:skill,:title,CAST(:snapshot AS jsonb),:now)
            ON CONFLICT(user_id,request_id) DO NOTHING
            """).param("id",UUID.randomUUID()).param("owner",owner).param("request",input.requestId()).param("exercise",exercise.id())
            .param("skill",exercise.skill().name()).param("title",exercise.title()).param("snapshot",mapper.writeValueAsString(snapshot)).param("now",OffsetDateTime.now(clock)).update();
        var id=jdbc.sql("SELECT id FROM skill_attempts WHERE user_id=:owner AND request_id=:request").param("owner",owner).param("request",input.requestId()).query(UUID.class).single();
        var stored=stored(owner,id);
        if(!stored.summary().exerciseId().equals(input.exerciseId()))throw conflict("Mã tạo lượt đã dùng cho bài khác.");
        return view(stored);
    }
    public Attempt get(String email,UUID id) { return view(stored(owner(email),id)); }
    private Stored stored(UUID owner,UUID id) {
        return jdbc.sql("SELECT * FROM skill_attempts WHERE user_id=:owner AND id=:id").param("owner",owner).param("id",id).query((rs,n)->{
            var summary=new Summary(id,rs.getString("exercise_id"),Skill.valueOf(rs.getString("skill")),rs.getString("title"),(Integer)rs.getObject("score"),rs.getObject("created_at",OffsetDateTime.class),rs.getObject("submitted_at",OffsetDateTime.class));
            String answers=rs.getString(summary.submittedAt()==null?"draft_answers":"answers");
            return new Stored(summary,mapper.readValue(rs.getString("snapshot"),Exercise.class),Arrays.asList(mapper.readValue(answers,Answer[].class)),rs.getLong("draft_version"));
        }).optional().orElseThrow(this::missing);
    }
    @Transactional
    public Attempt saveDraft(String email,UUID id,Draft input) {
        var owner=owner(email);
        jdbc.sql("SELECT id FROM skill_attempts WHERE user_id=:owner AND id=:id FOR UPDATE")
            .param("owner",owner).param("id",id).query(UUID.class).optional().orElseThrow(this::missing);
        var stored=stored(owner,id);
        if(stored.summary().submittedAt()!=null)throw conflict("Lượt luyện đã nộp, không thể sửa bản nháp.");
        if(stored.draftVersion()==input.version()+1 && stored.answers().equals(input.answers()))return view(stored);
        if(stored.draftVersion()!=input.version())throw conflict("Bản nháp đã thay đổi ở nơi khác. Hãy mở lại lượt luyện trước khi sửa.");
        var seen=new HashSet<String>();
        for(var answer:input.answers()) {
            if(!seen.add(answer.questionId()))throw bad("Câu trả lời bị trùng.");
            var question=stored.exercise().questions().stream().filter(q->q.id().equals(answer.questionId())).findFirst().orElseThrow(()->bad("Câu hỏi không thuộc lượt luyện này."));
            if(stored.exercise().skill()==Skill.SPEAKING) {
                if(!List.of("AGAIN","OK","CONFIDENT").contains(answer.value()))throw bad("Chọn mức tự đánh giá hợp lệ.");
            } else if(!question.options().isEmpty() && !question.options().contains(answer.value()))throw bad("Lựa chọn không hợp lệ.");
        }
        jdbc.sql("UPDATE skill_attempts SET draft_answers=CAST(:answers AS jsonb),draft_version=draft_version+1 WHERE id=:id AND user_id=:owner")
            .param("answers",mapper.writeValueAsString(input.answers())).param("id",id).param("owner",owner).update();
        return view(stored(owner,id));
    }
    @Transactional
    public Attempt submit(String email,UUID id,Submit input) {
        var owner=owner(email);
        jdbc.sql("SELECT id FROM skill_attempts WHERE user_id=:owner AND id=:id FOR UPDATE").param("owner",owner).param("id",id).query(UUID.class).optional().orElseThrow(this::missing);
        var stored=stored(owner,id);var exercise=stored.exercise();
        if(stored.summary().submittedAt()!=null) {
            if(!stored.answers().equals(input.answers()))throw conflict("Lượt luyện đã nộp. Hãy tạo lượt mới để luyện lại.");
            return view(stored);
        }
        var answers=new HashMap<String,String>();
        for(var answer:input.answers())if(answers.put(answer.questionId(),answer.value())!=null)throw bad("Câu trả lời bị trùng.");
        if(answers.size()!=exercise.questions().size())throw bad("Hãy hoàn thành tất cả câu hỏi.");
        int correct=0;
        for(var q:exercise.questions()) {
            var answer=answers.get(q.id());
            if(answer==null)throw bad("Câu hỏi không thuộc lượt luyện này.");
            if(exercise.skill()==Skill.SPEAKING) {
                if(!List.of("AGAIN","OK","CONFIDENT").contains(answer))throw bad("Chọn mức tự đánh giá hợp lệ.");
            } else if(!q.options().isEmpty() && !q.options().contains(answer))throw bad("Lựa chọn không hợp lệ.");
            if(matches(q,answer,exercise.skill()))correct++;
        }
        Integer score=exercise.skill()==Skill.SPEAKING?null:(int)Math.round(correct*100.0/exercise.questions().size());
        var now=OffsetDateTime.now(clock);
        jdbc.sql("UPDATE skill_attempts SET answers=CAST(:answers AS jsonb),score=:score,submitted_at=:now WHERE id=:id AND user_id=:owner")
            .param("answers",mapper.writeValueAsString(input.answers())).param("score",score).param("now",now).param("id",id).param("owner",owner).update();
        events.skill(owner,id,exercise.title(),score,now);
        return view(stored(owner,id));
    }
    private Attempt view(Stored stored) {
        boolean submitted=stored.summary().submittedAt()!=null;var exercise=stored.exercise();
        var answers=new HashMap<String,String>();stored.answers().forEach(a->answers.put(a.questionId(),a.value()));
        return new Attempt(stored.summary(),exercise.description(),exercise.questions().stream().map(q->new QuestionView(q.id(),q.prompt(),q.text(),q.hint(),q.options(),answers.get(q.id()),
            submitted && exercise.skill()!=Skill.SPEAKING?matches(q,answers.get(q.id()),exercise.skill()):null,
            submitted?q.accepted():null,submitted?q.explanation():null,q.audioSrc())).toList(),stored.draftVersion());
    }
    private boolean matches(Question q,String answer,Skill skill) {
        return q.accepted().stream().anyMatch(a->skill==Skill.WRITING?normalize(a).equals(normalize(answer)):a.equals(answer));
    }
    public static String normalize(String text) { return Normalizer.normalize(text,Normalizer.Form.NFKC).replaceAll("[\\p{Z}\\s，。！？、,.!?]", ""); }
    private ResponseStatusException bad(String text) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,text); }
    private ResponseStatusException conflict(String text) { return new ResponseStatusException(HttpStatus.CONFLICT,text); }
    private ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy bài hoặc lượt luyện."); }
}
