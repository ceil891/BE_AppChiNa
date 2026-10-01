package com.chineselearning.modules.foundation;

import com.chineselearning.modules.foundation.FoundationModels.*;
import com.chineselearning.modules.user.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;

@Service
public class FoundationService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final UserService users;
    private final Clock clock;
    private final com.chineselearning.modules.progress.service.LearningEventService events;
    public FoundationService(JdbcClient jdbc,ObjectMapper mapper,UserService users,Clock clock,com.chineselearning.modules.progress.service.LearningEventService events) {
        this.jdbc=jdbc;this.mapper=mapper;this.users=users;this.clock=clock;this.events=events;
    }
    private ResponseStatusException problem(HttpStatus status,String text) { return new ResponseStatusException(status,text); }
    private void require(String actor,String permission) {
        var codes=users.current(actor).permissions();
        if(!codes.contains("foundations.read") || !codes.contains(permission))throw problem(HttpStatus.FORBIDDEN,"Bạn không có quyền quản lý bài nhập môn.");
    }
    private UUID owner(String actor) {
        var user=users.current(actor);
        if(user.permissions().stream().anyMatch(p->List.of("vocabulary.read","lessons.read","quizzes.read","skills.read","foundations.read","users.read","roles.read").contains(p)))
            throw problem(HttpStatus.FORBIDDEN,"Hãy dùng tài khoản học viên để lưu tiến độ.");
        return user.id();
    }
    private Detail load(String slug,boolean published,String lock) {
        return jdbc.sql("SELECT content,position,published,version FROM foundation_lessons WHERE slug=:slug"+(published?" AND published=true":"")+lock)
            .param("slug",slug).query((rs,n)->new Detail(mapper.readValue(rs.getString(1),Content.class),rs.getInt(2),rs.getBoolean(3),rs.getLong(4)))
            .optional().orElseThrow(()->problem(HttpStatus.NOT_FOUND,"Không tìm thấy bài nhập môn hoặc bài chưa được xuất bản."));
    }
    public List<Card> list() {
        return jdbc.sql("SELECT content,position,version FROM foundation_lessons WHERE published=true ORDER BY position,slug")
            .query((rs,n)->{var c=mapper.readValue(rs.getString(1),Content.class);return new Card(c.slug(),c.title(),c.subtitle(),c.minutes(),c.symbol(),rs.getInt(2),rs.getLong(3));}).list();
    }
    public Lesson get(String slug) {
        var d=load(slug,true,"");var c=d.content();
        return new Lesson(c.slug(),c.title(),c.subtitle(),c.minutes(),c.symbol(),c.sections(),c.groups(),c.examples(),
            c.questions().stream().map(q->new PublicQuestion(q.prompt(),q.options())).toList(),c.toneChart(),d.version());
    }
    public List<Detail> adminList(String actor) {
        require(actor,"foundations.read");
        return jdbc.sql("SELECT content,position,published,version FROM foundation_lessons ORDER BY position,slug")
            .query((rs,n)->new Detail(mapper.readValue(rs.getString(1),Content.class),rs.getInt(2),rs.getBoolean(3),rs.getLong(4))).list();
    }
    public Detail adminGet(String actor,String slug) { require(actor,"foundations.read");return load(slug,false,""); }
    private void validate(Content content) {
        if(content.slug().equals("new"))throw problem(HttpStatus.BAD_REQUEST,"Đường dẫn này được dành riêng. Hãy chọn đường dẫn khác.");
        for(var q:content.questions()) {
            if(q.correct()>=q.options().size() || new HashSet<>(q.options().stream().map(String::strip).toList()).size()!=q.options().size())
                throw problem(HttpStatus.BAD_REQUEST,"Mỗi câu cần các lựa chọn khác nhau và một đáp án đúng hợp lệ.");
        }
        for(var example:content.examples()) {
            // Audio is served from the application; never accept scripts, arbitrary hosts or traversal.
            if(!example.audioSrc().matches("/audio/(foundations|skills)/[a-zA-Z0-9_-]+\\.mp3"))
                throw problem(HttpStatus.BAD_REQUEST,"Chọn đường dẫn MP3 trong /audio/foundations/ hoặc /audio/skills/.");
        }
    }
    private void version(Detail d,long version) {
        if(d.version()!=version)throw problem(HttpStatus.CONFLICT,"Nội dung đã thay đổi. Hãy tải lại bài trước khi tiếp tục.");
    }
    @Transactional public Detail create(String actor,Input input) {
        require(actor,"foundations.create");validate(input.content());
        if(input.version()!=0)throw problem(HttpStatus.BAD_REQUEST,"Phiên bản bài mới phải bằng 0.");
        int inserted=jdbc.sql("INSERT INTO foundation_lessons(slug,content,position) VALUES(:slug,CAST(:content AS jsonb),:position) ON CONFLICT(slug) DO NOTHING")
            .param("slug",input.content().slug()).param("content",mapper.writeValueAsString(input.content())).param("position",input.position()).update();
        if(inserted==0)throw problem(HttpStatus.CONFLICT,"Đường dẫn bài đã tồn tại.");
        return load(input.content().slug(),false,"");
    }
    @Transactional public Detail update(String actor,String slug,Input input) {
        require(actor,"foundations.update");var d=load(slug,false," FOR UPDATE");version(d,input.version());
        if(d.published())throw problem(HttpStatus.CONFLICT,"Gỡ xuất bản trước khi sửa bài.");
        if(!slug.equals(input.content().slug()))throw problem(HttpStatus.BAD_REQUEST,"Không đổi đường dẫn của bài đã tạo.");
        validate(input.content());
        jdbc.sql("UPDATE foundation_lessons SET content=CAST(:content AS jsonb),position=:position,version=version+1 WHERE slug=:slug")
            .param("slug",slug).param("content",mapper.writeValueAsString(input.content())).param("position",input.position()).update();
        return load(slug,false,"");
    }
    @Transactional public Detail publish(String actor,String slug,Publication input) {
        require(actor,"foundations.publish");var d=load(slug,false," FOR UPDATE");version(d,input.version());validate(d.content());
        jdbc.sql("UPDATE foundation_lessons SET published=:published,version=version+1 WHERE slug=:slug")
            .param("slug",slug).param("published",input.published()).update();return load(slug,false,"");
    }
    private List<Progress> progressFor(UUID id,String slug) {
        return jdbc.sql("SELECT p.* FROM foundation_progress p WHERE user_id=:owner AND (:slug='' OR slug=:slug) ORDER BY updated_at DESC,slug")
            .param("owner",id).param("slug",slug).query((rs,n)->new Progress(rs.getString("slug"),rs.getInt("section_index"),rs.getLong("lesson_version"),rs.getLong("version"),
                (Integer)rs.getObject("best_score"),(Integer)rs.getObject("last_score"),rs.getInt("attempts"),rs.getObject("completed_at",OffsetDateTime.class),rs.getObject("updated_at",OffsetDateTime.class),
                rs.getString("last_result")==null?null:mapper.readValue(rs.getString("last_result"),Result.class))).list();
    }
    public List<Progress> progress(String actor) { return progressFor(owner(actor),""); }
    private void lockOwner(UUID id) { jdbc.sql("SELECT id FROM users WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).single(); }
    @Transactional public Progress bookmark(String actor,String slug,Bookmark input) {
        UUID id=owner(actor);lockOwner(id);var lesson=load(slug,true," FOR SHARE");version(lesson,input.lessonVersion());
        if(input.sectionIndex()>=lesson.content().sections().size())throw problem(HttpStatus.BAD_REQUEST,"Vị trí đọc không hợp lệ.");
        var existing=progressFor(id,slug);
        long current=existing.isEmpty()?0:existing.get(0).version();
        if(input.version()!=current)throw problem(HttpStatus.CONFLICT,"Tiến độ đã thay đổi ở cửa sổ khác. Tải lại để tiếp tục.");
        jdbc.sql("""
            INSERT INTO foundation_progress(user_id,slug,section_index,lesson_version,version,updated_at)
            VALUES(:owner,:slug,:section,:lesson,1,:now)
            ON CONFLICT(user_id,slug) DO UPDATE SET section_index=:section,lesson_version=:lesson,version=foundation_progress.version+1,updated_at=:now
            """).param("owner",id).param("slug",slug).param("section",input.sectionIndex()).param("lesson",input.lessonVersion()).param("now",OffsetDateTime.now(clock)).update();
        return progressFor(id,slug).get(0);
    }
    @Transactional public Result submit(String actor,String slug,Submit input) {
        UUID id=actor==null?null:owner(actor);
        if(id!=null) {
            lockOwner(id);
            var replay=jdbc.sql("SELECT slug,lesson_version,answers,result FROM foundation_submissions WHERE user_id=:owner AND request_id=:request")
                .param("owner",id).param("request",input.requestId()).query((rs,n)->{
                    if(!rs.getString(1).equals(slug) || rs.getLong(2)!=input.lessonVersion() || !Arrays.asList(mapper.readValue(rs.getString(3),Integer[].class)).equals(input.answers()))
                        throw problem(HttpStatus.CONFLICT,"Mã nộp bài đã dùng cho nội dung khác.");
                    return mapper.readValue(rs.getString(4),Result.class);
                }).optional();
            if(replay.isPresent())return replay.get();
        }
        var d=load(slug,true," FOR SHARE");version(d,input.lessonVersion());var c=d.content();
        if(input.answers().size()!=c.questions().size())throw problem(HttpStatus.BAD_REQUEST,"Hãy trả lời đủ các câu hỏi.");
        var review=new ArrayList<Review>();int correct=0;
        for(int i=0;i<c.questions().size();i++) {
            var q=c.questions().get(i);int selected=input.answers().get(i);
            if(selected>=q.options().size())throw problem(HttpStatus.BAD_REQUEST,"Lựa chọn không hợp lệ.");
            if(selected==q.correct())correct++;
            review.add(new Review(q.prompt(),q.options(),selected,q.correct(),q.explanation()));
        }
        var now=OffsetDateTime.now(clock);
        var result=new Result(input.requestId(),slug,c.title(),d.version(),correct,review.size(),(int)Math.round(correct*100.0/review.size()),now,id!=null,review);
        if(id!=null) {
            events.foundation(id,input.requestId(),c.title(),result.score(),now);
            String json=mapper.writeValueAsString(result);
            jdbc.sql("INSERT INTO foundation_submissions(user_id,request_id,slug,lesson_version,answers,result,submitted_at) VALUES(:owner,:request,:slug,:version,CAST(:answers AS jsonb),CAST(:result AS jsonb),:now)")
                .param("owner",id).param("request",input.requestId()).param("slug",slug).param("version",d.version()).param("answers",mapper.writeValueAsString(input.answers())).param("result",json).param("now",now).update();
            jdbc.sql("""
                INSERT INTO foundation_progress(user_id,slug,section_index,lesson_version,version,best_score,last_score,attempts,completed_at,updated_at,last_result)
                VALUES(:owner,:slug,:section,:lesson,1,:score,:score,1,:now,:now,CAST(:result AS jsonb))
                ON CONFLICT(user_id,slug) DO UPDATE SET section_index=:section,lesson_version=:lesson,version=foundation_progress.version+1,
                best_score=GREATEST(foundation_progress.best_score,:score),last_score=:score,attempts=foundation_progress.attempts+1,
                completed_at=COALESCE(foundation_progress.completed_at,:now),updated_at=:now,last_result=CAST(:result AS jsonb)
                """).param("owner",id).param("slug",slug).param("section",c.sections().size()-1).param("lesson",d.version()).param("score",result.score()).param("result",json).param("now",now).update();
        }
        return result;
    }
}
