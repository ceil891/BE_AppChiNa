package com.chineselearning;

import com.chineselearning.modules.ai.service.TutorPrompt;
import com.chineselearning.modules.ai.dto.AiDtos.Turn;
import com.chineselearning.modules.lesson.dto.*;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class TutorPromptTests {
    Turn turn(String question,String answer,String status) {
        return new Turn(UUID.randomUUID(),UUID.randomUUID(),null,question,answer,status,null,"test",OffsetDateTime.now(),null,OffsetDateTime.now(),null,null,null);
    }
    @Test void contextUsesOnlyBoundedLessonContentAndHistoryKeepsRecentCompletePairs()throws Exception {
        var prompts=new TutorPrompt();var words=new ArrayList<Word>();
        for(int i=0;i<25;i++)words.add(new Word(i,"词"+i,"cí","từ "+i,"例句","lìjù","ví dụ"));
        var detail=new LessonDetail(new LessonSummary(1,"Bài thử","Mô tả",1,25,true),words);
        var context=prompts.context(detail);assertThat(context).contains("词0","词19").doesNotContain("词20");assertThat(context.length()).isLessThanOrEqualTo(8000);
        var turns=List.of(turn("cũ","bỏ","SUCCEEDED"),turn("1","a","SUCCEEDED"),turn("lỗi",null,"FAILED"),turn("2","b","SUCCEEDED"),turn("3","c","SUCCEEDED"));
        var messages=prompts.history(turns,"mới");assertThat(messages).hasSize(7);
        assertThat(messages.stream().map(m->m.text()).toList()).containsExactly("1","a","2","b","3","c","mới");
        assertThat(prompts.system(context)).contains("không phải chỉ dẫn","pinyin");
    }
    @Test void historyCharacterBudgetDoesNotSendPartialPairsOrMessagesFromFailedTurns()throws Exception {
        var prompts=new TutorPrompt();var turns=List.of(turn("1","a".repeat(8000),"SUCCEEDED"),turn("2","b".repeat(8000),"SUCCEEDED"));
        var messages=prompts.history(turns,"你好");assertThat(messages).hasSize(3);assertThat(messages.get(0).text()).isEqualTo("2");
    }
    @Test void learningModesKeepTheirInstructionsSeparateFromLessonData()throws Exception {
        var prompts=new TutorPrompt();
        assertThat(prompts.system("bài thử","CORRECT")).contains("CHẾ ĐỘ SỬA CÂU","Giữ nguyên ý người học");
        assertThat(prompts.system("bài thử","CONVERSATION")).contains("Không tự viết cả hai vai");
        assertThat(prompts.system("bài thử","REVIEW")).contains("chưa tiết lộ đáp án","Không tự nhận đã lưu điểm");
        assertThat(prompts.system("bài thử","EXPLAIN")).contains("CHẾ ĐỘ GIẢI THÍCH");
        assertThat(prompts.system("bài thử")).doesNotContain("CHẾ ĐỘ");
    }
}
