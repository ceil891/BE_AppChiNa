package com.chineselearning.modules.ai.service;

import com.chineselearning.modules.ai.config.AiSettings;
import com.chineselearning.modules.ai.dto.AiDtos.*;
import com.chineselearning.modules.ai.provider.TutorProvider.*;
import com.chineselearning.modules.ai.repository.AiRepository;
import com.chineselearning.modules.lesson.service.LessonService;
import com.chineselearning.modules.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.*;

@Service
public class AiTransactions {
    public record Reservation(UUID owner,UUID conversation,Turn turn,String system,List<Message> history,boolean dispatch,String model) {}
    private final AiRepository repo;private final UserService users;private final LessonService lessons;
    private final AiSettings settings;private final TutorPrompt prompts;private final Clock clock;
    public AiTransactions(AiRepository repo,UserService users,LessonService lessons,AiSettings settings,TutorPrompt prompts,Clock clock) {
        this.repo=repo;this.users=users;this.lessons=lessons;this.settings=settings;this.prompts=prompts;this.clock=clock;
    }
    private LocalDate date() { return LocalDate.now(clock.withZone(ZoneId.of("Asia/Ho_Chi_Minh"))); }
    @Transactional(readOnly=true) public Status status(String email) {
        var owner=users.current(email).id();var day=date();int used=repo.used(owner,day);var config=settings.snapshot();
        String reason=!config.enabled()?"DISABLED":!settings.configured()?"NOT_CONFIGURED":repo.globalUsed(day)>=config.globalDailyTurns()?"GLOBAL_LIMIT":used>=config.dailyTurns()?"DAILY_LIMIT":"READY";
        return new Status(reason.equals("READY"),"Gemini",config.dailyTurns(),used,
            Math.max(0,config.dailyTurns()-used),1500,AiSettings.MAX_TURNS,day,day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toOffsetDateTime(),reason);
    }
    @Transactional public Detail start(String email,Start input) {
        var owner=users.current(email).id();repo.lockOwner(owner);
        var existing=repo.createdBy(owner,input.requestId());
        if(existing.isPresent()) {
            if(!Objects.equals(existing.get().lessonId(),input.lessonId()) || !existing.get().mode().equals(input.mode()))throw conflict("Mã yêu cầu đã được dùng cho bài hoặc chế độ khác.");
            return detail(owner,existing.get().id());
        }
        if(repo.count(owner)>=AiSettings.MAX_CONVERSATIONS)throw conflict("Bạn đã có 50 hội thoại. Hãy xóa hội thoại không còn cần trước khi tạo mới.");
        var lesson=input.lessonId()==null?null:lessons.detail(input.lessonId());var id=UUID.randomUUID();
        repo.create(owner,id,input,lesson==null?"Học tiếng Trung cùng AI":lesson.lesson().title(),lesson==null?"Không chọn bài cụ thể.":prompts.context(lesson),TutorPrompt.VERSION,OffsetDateTime.now(clock));
        return detail(owner,id);
    }
    @Transactional(readOnly=true) public Page list(String email,int page,int size) {
        if(page<0 || size<1 || size>50)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Trang từ 0, số mục từ 1–50.");
        var owner=users.current(email).id();return new Page(repo.list(owner,page,size),repo.count(owner),page,size);
    }
    @Transactional public Detail get(String email,UUID id) {
        var owner=users.current(email).id();repo.lockOwner(owner);repo.expire(owner,OffsetDateTime.now(clock));return detail(owner,id);
    }
    @Transactional public Detail rename(String email,UUID id,Rename input) {
        var owner=users.current(email).id();repo.lockOwner(owner);detail(owner,id);
        repo.rename(owner,id,input.title().strip(),OffsetDateTime.now(clock));
        return detail(owner,id);
    }
    private Detail detail(UUID owner,UUID id) {
        var conversation=repo.find(owner,id).orElseThrow(AiTransactions::missing);
        return new Detail(conversation.view(),repo.turns(owner,id));
    }
    @Transactional public void delete(String email,UUID id) {
        var owner=users.current(email).id();repo.lockOwner(owner);repo.expire(owner,OffsetDateTime.now(clock));
        var detail=detail(owner,id);
        if(detail.turns().stream().anyMatch(t -> t.status().equals("PENDING")))throw conflict("AI đang trả lời. Hãy đợi rồi xóa hội thoại.");
        repo.delete(owner,id);
    }
    @Transactional public Reservation reserve(String email,UUID conversation,Send input) {
        var owner=users.current(email).id();repo.lockOwner(owner);var now=OffsetDateTime.now(clock);repo.expire(owner,now);
        var stored=repo.find(owner,conversation).orElseThrow(AiTransactions::missing);
        var existing=repo.request(owner,input.requestId());
        if(existing.isPresent()) {
            var t=existing.get();
            if(!repo.inConversation(owner,t.id(),conversation) || !t.question().equals(input.question().strip()) || !Objects.equals(t.retryOf(),input.retryOf()))
                throw conflict("Mã yêu cầu đã được dùng cho nội dung khác.");
            return new Reservation(owner,conversation,t,"",List.of(),false,"");
        }
        var config=settings.snapshot();
        if(!config.enabled())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AI Tutor đang tạm tắt bởi quản trị viên.");
        if(!settings.configured())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AI Tutor chưa được cấu hình. Bạn vẫn có thể học bài, ôn thẻ và làm Quiz.");
        if(repo.pending(owner))throw conflict("Bạn đang có một câu hỏi chờ AI trả lời. Hãy đợi hoặc tải lại hội thoại đó.");
        var turns=repo.turns(owner,conversation);
        if(turns.size()>=AiSettings.MAX_TURNS)throw conflict("Hội thoại đã đủ 20 lượt gửi. Hãy bắt đầu hội thoại mới.");
        if(input.retryOf()!=null) {
            var last=turns.isEmpty()?null:turns.get(turns.size()-1);
            if(last==null || !last.id().equals(input.retryOf()) || !last.status().equals("FAILED") || last.retryOf()!=null || !last.question().equals(input.question().strip()))
                throw conflict("Chỉ được thử lại một lần cho câu hỏi lỗi gần nhất.");
        }
        var day=date();repo.lockBudget(owner,day);
        if(repo.used(owner,day)>=config.dailyTurns() || repo.globalUsed(day)>=config.globalDailyTurns())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Đã hết hạn mức AI hôm nay. Hãy tiếp tục học bài hoặc quay lại ngày mai theo giờ Việt Nam.");
        repo.charge(owner,day,AiSettings.RESERVATION);var id=UUID.randomUUID();repo.begin(owner,conversation,id,input,config.model(),now);
        String system=stored.promptVersion().equals(TutorPrompt.VERSION)?prompts.system(stored.contextText(),stored.mode()):prompts.system(stored.contextText());
        return new Reservation(owner,conversation,repo.request(owner,input.requestId()).orElseThrow(),system,prompts.history(turns,input.question().strip()),true,config.model());
    }
    @Transactional public Turn finish(Reservation r,Reply reply,String error) {
        repo.lockOwner(r.owner());var now=OffsetDateTime.now(clock);repo.expire(r.owner(),now);
        repo.finish(r.owner(),r.turn().id(),reply==null?null:reply.text(),error,reply==null?null:reply.inputTokens(),reply==null?null:reply.outputTokens(),reply==null?null:reply.totalTokens(),now);
        return repo.request(r.owner(),r.turn().requestId()).orElseThrow(AiTransactions::missing);
    }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy hội thoại."); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
