package com.chineselearning.modules.skills;

import org.springframework.stereotype.Component;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import tools.jackson.databind.ObjectMapper;

/** Versioned introductory content; attempts retain their own immutable copy. */
@Component
@DependsOnDatabaseInitialization
public class SkillCatalog {
    public enum Skill { LISTENING, SPEAKING, READING, WRITING }
    public record Question(String id,String prompt,String text,String hint,List<String> options,List<String> accepted,String explanation,String audioSrc) {
        public Question(String id,String prompt,String text,String hint,List<String> options,List<String> accepted,String explanation) {
            this(id,prompt,text,hint,options,accepted,explanation,null);
        }
    }
    public record Exercise(String id,Skill skill,String title,String description,List<Question> questions) {}
    private static Question choice(String id,String prompt,String text,String explanation,String correct,String... others) {
        var options=new ArrayList<String>();options.add(correct);options.addAll(List.of(others));
        // Fixed, non-answer-first order in the catalogue. Each attempt shuffles again.
        Collections.rotate(options,Integer.parseInt(id)%4);
        return new Question(id,prompt,text,"",List.copyOf(options),List.of(correct),explanation);
    }
    private static Question speak(String id,String text,String hint) {
        return new Question(id,"Nghe mẫu, đọc thành tiếng, thu âm rồi nghe lại.",text,hint,List.of(),List.of(),"Tự kiểm tra: đọc đủ từ, ngắt câu tự nhiên và đối chiếu thanh điệu với mẫu.");
    }
    private static Question write(String id,String prompt,String hint,String answer,String explanation) {
        return new Question(id,prompt,"",hint,List.of(),List.of(answer),explanation);
    }
    private final List<Exercise> exercises=List.of(
        new Exercise("listening-greetings-v1",Skill.LISTENING,"Nghe lời chào","Nghe câu tiếng Trung và chọn nghĩa tiếng Việt.",List.of(
            choice("1","Câu vừa nghe có nghĩa gì?","你好！","你好 (nǐ hǎo) là lời chào thông dụng.","Xin chào!","Tạm biệt!","Cảm ơn!","Xin lỗi!"),
            choice("2","Người nói đang làm gì?","谢谢你。","谢谢你 (xièxie nǐ): cảm ơn bạn.","Cảm ơn bạn","Hỏi tên bạn","Chào tạm biệt","Hỏi đường"),
            choice("3","Chọn nghĩa của câu vừa nghe.","明天见！","明天见 (míngtiān jiàn): hẹn gặp ngày mai.","Hẹn gặp ngày mai!","Chào buổi sáng!","Tôi khỏe!","Bạn tên gì?"))),
        new Exercise("listening-daily-v1",Skill.LISTENING,"Nghe sinh hoạt hằng ngày","Nhận biết giờ giấc, đồ uống và địa điểm.",List.of(
            choice("1","Người nói muốn uống gì?","我想喝水。","水 (shuǐ) là nước.","Nước","Trà","Cà phê","Sữa"),
            choice("2","Người nói đi đâu?","我去学校。","学校 (xuéxiào) là trường học.","Trường học","Bệnh viện","Nhà hàng","Ngân hàng"),
            choice("3","Bây giờ là mấy giờ?","现在八点。","八点 (bā diǎn) là tám giờ.","8 giờ","6 giờ","9 giờ","10 giờ"))),
        new Exercise("speaking-introduction-v1",Skill.SPEAKING,"Nói: giới thiệu bản thân","Luyện ba câu ngắn; nghe lại và tự đánh giá từng câu.",List.of(
            speak("1","你好，很高兴认识你。","Nǐ hǎo, hěn gāoxìng rènshi nǐ. — Xin chào, rất vui được biết bạn."),
            speak("2","我是越南人。","Wǒ shì Yuènán rén. — Tôi là người Việt Nam."),
            speak("3","我喜欢学习中文。","Wǒ xǐhuan xuéxí Zhōngwén. — Tôi thích học tiếng Trung."))),
        new Exercise("speaking-shopping-v1",Skill.SPEAKING,"Nói: mua sắm","Luyện hỏi giá, gọi đồ uống và cảm ơn.",List.of(
            speak("1","这个多少钱？","Zhège duōshao qián? — Cái này bao nhiêu tiền?"),
            speak("2","我要一杯茶。","Wǒ yào yì bēi chá. — Tôi muốn một tách trà."),
            speak("3","谢谢，再见！","Xièxie, zàijiàn! — Cảm ơn, tạm biệt!"))),
        new Exercise("reading-friend-v1",Skill.READING,"Đọc: người bạn mới","Đọc đoạn giới thiệu và tìm thông tin chính.",List.of(
            choice("1","Bạn trong đoạn văn tên gì?","我叫小明。我是学生。我住在河内。我喜欢喝茶。","我叫小明: tôi tên là Tiểu Minh.","Tiểu Minh","Tiểu Lan","Tiểu Vương","Tiểu Lý"),
            choice("2","Tiểu Minh sống ở đâu?","我叫小明。我是学生。我住在河内。我喜欢喝茶。","我住在河内: tôi sống ở Hà Nội.","Hà Nội","Bắc Kinh","Thượng Hải","Đà Nẵng"),
            choice("3","Tiểu Minh thích uống gì?","我叫小明。我是学生。我住在河内。我喜欢喝茶。","我喜欢喝茶: tôi thích uống trà.","Trà","Nước","Sữa","Cà phê"))),
        new Exercise("reading-weekend-v1",Skill.READING,"Đọc: một ngày cuối tuần","Hiểu thời gian và hoạt động trong đoạn văn ngắn.",List.of(
            choice("1","Hôm nay là thứ mấy?","今天是星期六。上午我和朋友去书店。下午我们在家看书。晚上我学习中文。","星期六: thứ Bảy.","Thứ Bảy","Thứ Hai","Thứ Sáu","Chủ nhật"),
            choice("2","Buổi sáng họ đi đâu?","今天是星期六。上午我和朋友去书店。下午我们在家看书。晚上我学习中文。","上午……去书店: buổi sáng đi nhà sách.","Nhà sách","Trường học","Công viên","Nhà hàng"),
            choice("3","Buổi tối người kể làm gì?","今天是星期六。上午我和朋友去书店。下午我们在家看书。晚上我学习中文。","晚上我学习中文: buổi tối tôi học tiếng Trung.","Học tiếng Trung","Xem phim","Đi mua sắm","Chơi bóng"))),
        new Exercise("writing-words-v1",Skill.WRITING,"Viết: từ thông dụng","Nhập Hán tự theo nghĩa cho sẵn bằng bàn phím tiếng Trung.",List.of(
            write("1","Viết từ “xin chào” bằng Hán tự.","Pinyin: nǐ hǎo","你好","你 (bạn) + 好 (tốt) tạo thành lời chào 你好."),
            write("2","Viết từ “cảm ơn” bằng Hán tự.","Pinyin: xièxie","谢谢","谢谢 là cách cảm ơn thông dụng."),
            write("3","Viết từ “trường học” bằng Hán tự.","Pinyin: xuéxiào","学校","学校 nghĩa là trường học."))),
        new Exercise("writing-sentences-v1",Skill.WRITING,"Viết: sắp xếp câu","Viết lại các từ cho sẵn thành câu đúng thứ tự.",List.of(
            write("1","Sắp xếp: 中文 / 我 / 学习","Nghĩa: Tôi học tiếng Trung.","我学习中文","Thứ tự: chủ ngữ 我 + động từ 学习 + tân ngữ 中文."),
            write("2","Sắp xếp: 茶 / 喜欢 / 我 / 喝","Nghĩa: Tôi thích uống trà.","我喜欢喝茶","我 + 喜欢 + 喝茶: tôi thích uống trà."),
            write("3","Sắp xếp: 是 / 他 / 学生","Nghĩa: Anh ấy là học sinh.","他是学生","他 + 是 + 学生: anh ấy là học sinh.")))
    );
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    public SkillCatalog(JdbcClient jdbc,ObjectMapper mapper) {
        this.jdbc=jdbc;this.mapper=mapper;
        for(var e:exercises) {
            var questions=e.questions().stream().map(q->new Question(q.id(),q.prompt(),q.text(),q.hint(),q.options(),q.accepted(),q.explanation(),
                e.skill()==Skill.LISTENING || e.skill()==Skill.SPEAKING ? "/audio/skills/"+e.id()+"-"+q.id()+".mp3" : "")).toList();
            var seed=new Exercise(e.id(),e.skill(),e.title(),e.description(),questions);
            jdbc.sql("INSERT INTO skill_exercises(id,skill,content,published) VALUES(:id,:skill,CAST(:content AS jsonb),true) ON CONFLICT(id) DO NOTHING")
                .param("id",e.id()).param("skill",e.skill().name()).param("content",mapper.writeValueAsString(seed)).update();
        }
    }
    public List<Exercise> all() { return jdbc.sql("SELECT content FROM skill_exercises WHERE published ORDER BY id").query((rs,n)->mapper.readValue(rs.getString(1),Exercise.class)).list(); }
    public Optional<Exercise> find(String id) { return jdbc.sql("SELECT content FROM skill_exercises WHERE id=:id AND published").param("id",id).query((rs,n)->mapper.readValue(rs.getString(1),Exercise.class)).optional(); }
}
