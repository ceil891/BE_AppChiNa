package com.chineselearning;
import com.chineselearning.modules.flashcard.service.ReviewSchedule;
import com.chineselearning.modules.flashcard.dto.FlashcardDtos.Rating;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewScheduleTests {
    @Test void rememberingUsesOneThreeSevenFourteenAndThirtyDaysThenCaps(){
        var now=OffsetDateTime.parse("2026-09-14T08:00:00Z");int streak=0;
        for(int expected: new int[]{1,3,7,14,30,30,30}){
            var result=ReviewSchedule.next(streak,Rating.REMEMBER,now);
            assertThat(result.dueAt()).isEqualTo(now.plusDays(expected));streak=result.successStreak();
        }
        assertThat(streak).isEqualTo(5);
    }
    @Test void forgettingSchedulesTenMinutesAndRestartsTheRememberSequence(){
        var now=OffsetDateTime.parse("2026-09-14T23:58:00+07:00");
        var again=ReviewSchedule.next(5,Rating.AGAIN,now);
        assertThat(again.successStreak()).isZero();assertThat(again.dueAt()).isEqualTo(OffsetDateTime.parse("2026-09-15T00:08:00+07:00"));
        assertThat(ReviewSchedule.next(again.successStreak(),Rating.REMEMBER,again.dueAt()).dueAt()).isEqualTo(again.dueAt().plusDays(1));
    }
}
