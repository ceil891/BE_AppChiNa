package com.chineselearning.modules.quiz.service;

import com.chineselearning.modules.quiz.dto.QuizDtos.*;
import com.chineselearning.modules.quiz.repository.QuizRepository;
import com.chineselearning.modules.quiz.repository.QuizRepository.StoredQuestion;
import com.chineselearning.modules.user.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.chineselearning.modules.progress.service.LearningEventService;

@Service
public class QuizService {
    private final QuizRepository quizzes;
    private final UserService users;
    private final Clock clock;
    private final LearningEventService events;
    public QuizService(QuizRepository quizzes, UserService users, Clock clock, LearningEventService events) { this.quizzes=quizzes; this.users=users; this.clock=clock; this.events=events; }
    public List<SetSummary> sets() { return quizzes.sets(); }
    private OffsetDateTime now() { return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS); }

    @Transactional
    public Attempt start(String email, Start input) {
        if (input.questionCount()!=5 && input.questionCount()!=10) throw problem(HttpStatus.BAD_REQUEST, "Chọn 5 hoặc 10 câu cho mỗi lượt.");
        var owner=users.current(email).id();
        var existing=quizzes.byRequest(owner,input.requestId());
        if (existing.isPresent()) return replayStart(owner,existing.get(),input);
        // Hold the bank stable until every attempt question has been snapshotted.
        if (!quizzes.lockSet(input.setId())) throw problem(HttpStatus.NOT_FOUND,"Không tìm thấy bộ câu hỏi khả dụng.");
        var set=quizzes.set(input.setId()).orElseThrow(() -> problem(HttpStatus.NOT_FOUND,"Không tìm thấy bộ câu hỏi khả dụng."));
        var bank=new ArrayList<>(quizzes.bank(set.id()));
        if (bank.size()<input.questionCount()) throw problem(HttpStatus.CONFLICT,"Bộ này chưa đủ số câu đã chọn.");
        // Validate editorial choices before creating an attempt; never build ambiguous duplicate options.
        for (var q:bank) if (q.options().size()!=4 || q.options().stream().anyMatch(String::isBlank)
            || q.options().stream().map(String::strip).distinct().count()!=4 || q.correctIndex()<0 || q.correctIndex()>3)
            throw problem(HttpStatus.CONFLICT,"Bộ câu hỏi cần được biên tập lại trước khi làm.");
        UUID id=UUID.randomUUID();
        if (!quizzes.insert(id,owner,input,set.title(),now()))
            return replayStart(owner,quizzes.byRequest(owner,input.requestId()).orElseThrow(this::missing),input);
        Collections.shuffle(bank);
        for (int i=0;i<input.questionCount();i++) {
            var q=bank.get(i);var options=new ArrayList<Choice>();
            for (var text:q.options()) options.add(new Choice(UUID.randomUUID(),text));
            UUID correct=options.get(q.correctIndex()).id();Collections.shuffle(options);
            quizzes.insertQuestion(id,new StoredQuestion(UUID.randomUUID(),i+1,q.type(),q.prompt(),options,correct,null,q.explanation()));
        }
        return view(owner,id);
    }
    private Attempt replayStart(UUID owner, Summary existing, Start input) {
        if (existing.setId()!=input.setId() || existing.questionCount()!=input.questionCount())
            throw problem(HttpStatus.CONFLICT,"Mã tạo lượt đã dùng với bộ câu hỏi hoặc số câu khác.");
        return view(owner,existing.id());
    }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Attempt get(String email, UUID id) { return view(users.current(email).id(),id); }
    private Attempt view(UUID owner, UUID id) {
        var attempt=quizzes.get(owner,id).orElseThrow(this::missing);
        boolean submitted=attempt.submittedAt()!=null;
        var questions=quizzes.questions(owner,id).stream().map(q -> new Question(q.id(),q.position(),q.type(),q.prompt(),q.options(),q.selectedOptionId(),
            submitted?q.correctOptionId():null,submitted?Objects.equals(q.selectedOptionId(),q.correctOptionId()):null,submitted?q.explanation():null)).toList();
        return new Attempt(attempt,questions);
    }
    @Transactional
    public Attempt answer(String email, UUID id, Answer input) {
        var owner=users.current(email).id();
        if (!quizzes.lock(owner,id)) throw missing();
        var receipt=quizzes.receipt(id,input.requestId());
        if (receipt.isPresent()) {
            var old=receipt.get();
            if (!old.questionId().equals(input.questionId()) || !old.optionId().equals(input.optionId()) || old.requestVersion()!=input.version())
                throw problem(HttpStatus.CONFLICT,"Mã lưu câu trả lời đã được sử dụng với nội dung khác.");
            return view(owner,id);
        }
        var attempt=quizzes.get(owner,id).orElseThrow(this::missing);
        ensureEditable(attempt,input.version());
        var q=quizzes.questions(owner,id).stream().filter(question -> question.id().equals(input.questionId())).findFirst()
            .orElseThrow(() -> problem(HttpStatus.BAD_REQUEST,"Câu hỏi không thuộc lượt làm này."));
        if (q.options().stream().noneMatch(option -> option.id().equals(input.optionId())))
            throw problem(HttpStatus.BAD_REQUEST,"Lựa chọn không thuộc câu hỏi này.");
        quizzes.answer(owner,id,input);
        return view(owner,id);
    }
    @Transactional
    public Attempt submit(String email, UUID id, Submit input) {
        var owner=users.current(email).id();
        if (!quizzes.lock(owner,id)) throw missing();
        var attempt=quizzes.get(owner,id).orElseThrow(this::missing);
        if (attempt.submittedAt()!=null) return view(owner,id);
        ensureEditable(attempt,input.version());
        var questions=quizzes.questions(owner,id);
        if (questions.size()!=attempt.questionCount() || questions.stream().anyMatch(q -> q.selectedOptionId()==null))
            throw problem(HttpStatus.CONFLICT,"Hãy trả lời tất cả câu hỏi trước khi nộp bài.");
        int correct=(int)questions.stream().filter(q -> q.correctOptionId().equals(q.selectedOptionId())).count();
        int score=(int)Math.round(correct*100.0/attempt.questionCount());
        var submittedAt=now();
        quizzes.submit(owner,id,correct,score,submittedAt);
        events.quiz(owner,id,attempt.title(),score,submittedAt);
        return view(owner,id);
    }
    private void ensureEditable(Summary attempt,long version) {
        if (attempt.submittedAt()!=null) throw problem(HttpStatus.CONFLICT,"Bài đã nộp, không thể đổi câu trả lời.");
        if (attempt.version()!=version) throw problem(HttpStatus.CONFLICT,"Lượt làm đã thay đổi ở nơi khác. Hãy tải lại trước khi tiếp tục.");
    }
    public AttemptPage list(String email,String status,int page,int size) {
        if (page<0 || size<1 || size>50 || !List.of("","IN_PROGRESS","SUBMITTED").contains(status))
            throw problem(HttpStatus.BAD_REQUEST,"Bộ lọc không hợp lệ; trang từ 0, số mục từ 1–50.");
        var owner=users.current(email).id();
        return new AttemptPage(quizzes.list(owner,status,page,size),quizzes.count(owner,status),page,size);
    }
    private ResponseStatusException missing() { return problem(HttpStatus.NOT_FOUND,"Không tìm thấy lượt làm bài."); }
    private ResponseStatusException problem(HttpStatus status,String message) { return new ResponseStatusException(status,message); }
}
