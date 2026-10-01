package com.chineselearning.modules.skills;

import com.chineselearning.modules.skills.SkillCatalog.*;
import com.chineselearning.modules.user.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.*;

@Service
public class AdminSkillService {
    public record Item(@NotBlank @Size(max=500) String prompt,@NotNull @Size(max=4000) String text,
        @NotNull @Size(max=1000) String hint,@NotNull @Size(max=6) List<@NotBlank @Size(max=500) String> options,
        @NotNull @Size(max=10) List<@NotBlank @Size(max=500) String> accepted,
        @NotNull @Size(max=1500) String explanation,@NotNull @Size(max=1000) String audioSrc) {}
    public record Input(@NotBlank @Size(max=160) String title,@NotBlank @Size(max=500) String description,
        @NotNull @PositiveOrZero Long version,@NotNull @Size(min=1,max=20) List<@NotNull @Valid Item> questions) {}
    public record Publication(@NotNull @PositiveOrZero Long version,@NotNull Boolean published) {}
    public record Detail(Exercise exercise,boolean published,long version) {}
    public record Result(UUID id,UUID userId,String displayName,String title,Integer score,java.time.OffsetDateTime submittedAt) {}
    public record Results(List<Result> items,long total,int page,int size) {}
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final UserService users;
    public AdminSkillService(JdbcClient jdbc,ObjectMapper mapper,UserService users) { this.jdbc=jdbc;this.mapper=mapper;this.users=users; }
    private void require(String actor,String permission) {
        var codes=users.current(actor).permissions();
        if(!codes.contains("skills.read") || !codes.contains(permission))throw problem(HttpStatus.FORBIDDEN,"Bạn không có quyền thực hiện thao tác này.");
    }
    public List<Detail> list(String actor,Skill skill) {
        require(actor,"skills.read");
        return jdbc.sql("SELECT content,published,version FROM skill_exercises WHERE skill=:skill ORDER BY id")
            .param("skill",skill.name()).query((rs,n)->new Detail(mapper.readValue(rs.getString(1),Exercise.class),rs.getBoolean(2),rs.getLong(3))).list();
    }
    public Detail get(String actor,Skill skill,String id) { require(actor,"skills.read");return load(skill,id,false); }
    private Detail load(Skill skill,String id,boolean lock) {
        return jdbc.sql("SELECT content,published,version FROM skill_exercises WHERE skill=:skill AND id=:id"+(lock?" FOR UPDATE":""))
            .param("skill",skill.name()).param("id",id).query((rs,n)->new Detail(mapper.readValue(rs.getString(1),Exercise.class),rs.getBoolean(2),rs.getLong(3)))
            .optional().orElseThrow(()->problem(HttpStatus.NOT_FOUND,"Không tìm thấy bài luyện."));
    }
    private Exercise validate(Skill skill,String id,Input input) {
        var questions=new ArrayList<Question>();
        for(var q:input.questions()) {
            boolean choice=skill==Skill.LISTENING || skill==Skill.READING;
            if(choice && (q.options().size()<2 || new HashSet<>(q.options()).size()!=q.options().size() || q.accepted().size()!=1 || !q.options().contains(q.accepted().get(0))))throw bad("Câu trắc nghiệm cần 2–6 lựa chọn khác nhau và một đáp án thuộc các lựa chọn.");
            if(!choice && !q.options().isEmpty())throw bad("Bài Nói và Viết không có lựa chọn trắc nghiệm.");
            if(skill==Skill.SPEAKING && !q.accepted().isEmpty())throw bad("Bài Nói dùng tự đánh giá, không có đáp án chấm điểm.");
            if(skill==Skill.WRITING && (q.accepted().isEmpty() || q.accepted().stream().anyMatch(a->SkillService.normalize(a).isEmpty())))throw bad("Bài Viết cần ít nhất một đáp án có nội dung.");
            if(skill!=Skill.WRITING && q.text().isBlank())throw bad("Hãy nhập nội dung tiếng Trung.");
            if(q.explanation().isBlank())throw bad("Hãy nhập giải thích hoặc tiêu chí tự đánh giá.");
            if(!q.audioSrc().isBlank()) {
                try {
                    var uri=URI.create(q.audioSrc());
                    if(!q.audioSrc().matches("/audio/skills/[a-zA-Z0-9_-]+\\.mp3") && !("https".equals(uri.getScheme()) && uri.getHost()!=null && uri.getUserInfo()==null))throw new IllegalArgumentException();
                } catch(IllegalArgumentException e) { throw bad("Âm thanh phải là liên kết HTTPS hoặc file mẫu có sẵn."); }
            }
            if((skill==Skill.LISTENING || skill==Skill.SPEAKING) && q.audioSrc().isBlank())throw bad("Bài Nghe và Nói cần liên kết âm thanh mẫu.");
            questions.add(new Question(String.valueOf(questions.size()+1),q.prompt(),q.text(),q.hint(),q.options(),q.accepted(),q.explanation(),q.audioSrc()));
        }
        return new Exercise(id,skill,input.title().trim(),input.description().trim(),questions);
    }
    @Transactional
    public Detail create(String actor,Skill skill,Input input) {
        require(actor,"skills.create");
        if(input.version()!=0)throw bad("Phiên bản bài mới phải là 0.");
        String id=UUID.randomUUID().toString();var exercise=validate(skill,id,input);
        jdbc.sql("INSERT INTO skill_exercises(id,skill,content) VALUES(:id,:skill,CAST(:content AS jsonb))")
            .param("id",id).param("skill",skill.name()).param("content",mapper.writeValueAsString(exercise)).update();
        return new Detail(exercise,false,0);
    }
    @Transactional
    public Detail update(String actor,Skill skill,String id,Input input) {
        require(actor,"skills.update");var current=load(skill,id,true);checkVersion(current,input.version());
        if(current.published())throw problem(HttpStatus.CONFLICT,"Ẩn bài trước khi sửa nội dung.");
        var exercise=validate(skill,id,input);
        jdbc.sql("UPDATE skill_exercises SET content=CAST(:content AS jsonb),version=version+1 WHERE id=:id")
            .param("id",id).param("content",mapper.writeValueAsString(exercise)).update();
        return new Detail(exercise,false,current.version()+1);
    }
    @Transactional
    public Detail publish(String actor,Skill skill,String id,Publication input) {
        require(actor,"skills.publish");var current=load(skill,id,true);checkVersion(current,input.version());
        jdbc.sql("UPDATE skill_exercises SET published=:published,version=version+1 WHERE id=:id")
            .param("id",id).param("published",input.published()).update();
        return new Detail(current.exercise(),input.published(),current.version()+1);
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Results results(String actor,Skill skill,String id,int page,int size) {
        require(actor,"skills.read");var codes=users.current(actor).permissions();
        if(!codes.contains("users.read") || !codes.contains("users.skills.read"))throw problem(HttpStatus.FORBIDDEN,"Bạn không có quyền xem kết quả học viên.");
        load(skill,id,false);
        if(page<0 || size<1 || size>50)throw bad("Phân trang không hợp lệ.");
        var items=jdbc.sql("SELECT a.id,a.user_id,u.display_name,a.title,a.score,a.submitted_at FROM skill_attempts a JOIN users u ON u.id=a.user_id WHERE a.exercise_id=:id AND a.submitted_at IS NOT NULL ORDER BY a.submitted_at DESC,a.id DESC LIMIT :size OFFSET :offset")
            .param("id",id).param("size",size).param("offset",(long)page*size).query(Result.class).list();
        var total=jdbc.sql("SELECT count(*) FROM skill_attempts WHERE exercise_id=:id AND submitted_at IS NOT NULL").param("id",id).query(Long.class).single();
        return new Results(items,total,page,size);
    }
    private void checkVersion(Detail detail,long version) { if(detail.version()!=version)throw problem(HttpStatus.CONFLICT,"Bài đã được thay đổi. Tải lại trước khi lưu."); }
    private ResponseStatusException bad(String message) { return problem(HttpStatus.BAD_REQUEST,message); }
    private ResponseStatusException problem(HttpStatus status,String message) { return new ResponseStatusException(status,message); }
}
