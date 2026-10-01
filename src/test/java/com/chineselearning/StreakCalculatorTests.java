package com.chineselearning;

import com.chineselearning.modules.progress.service.StreakCalculator;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class StreakCalculatorTests {
    @Test void duplicateDaysAndFutureDatesDoNotInflateStreak() {
        var today=LocalDate.of(2026,9,14);
        var result=StreakCalculator.calculate(List.of(today,today,today.minusDays(1),today.plusDays(1)),today);
        assertThat(result.current()).isEqualTo(2);assertThat(result.longest()).isEqualTo(2);assertThat(result.activeDays()).isEqualTo(2);
        assertThat(result.studiedToday()).isTrue();assertThat(result.lastStudyDate()).isEqualTo(today);
    }
    @Test void yesterdayKeepsStreakAliveUntilAFullDayIsMissed() {
        var today=LocalDate.of(2026,9,14);var days=List.of(today.minusDays(3),today.minusDays(2),today.minusDays(1));
        var active=StreakCalculator.calculate(days,today);assertThat(active.current()).isEqualTo(3);assertThat(active.studiedToday()).isFalse();
        var broken=StreakCalculator.calculate(days,today.plusDays(1));assertThat(broken.current()).isZero();assertThat(broken.longest()).isEqualTo(3);
        assertThat(broken.activeDays()).isEqualTo(3);
    }
    @Test void leapDayAndYearBoundaryAreConsecutiveButGapsBreakRuns() {
        var result=StreakCalculator.calculate(List.of(LocalDate.of(2024,2,28),LocalDate.of(2024,2,29),LocalDate.of(2024,3,1),
            LocalDate.of(2024,12,31),LocalDate.of(2025,1,1)),LocalDate.of(2025,1,1));
        assertThat(result.current()).isEqualTo(2);assertThat(result.longest()).isEqualTo(3);assertThat(result.activeDays()).isEqualTo(5);
    }
    @Test void emptyHistoryDoesNotClaimAStudyDay() {
        var result=StreakCalculator.calculate(List.of(),LocalDate.of(2026,9,14));
        assertThat(result.current()).isZero();assertThat(result.longest()).isZero();assertThat(result.activeDays()).isZero();
        assertThat(result.lastStudyDate()).isNull();assertThat(result.studiedToday()).isFalse();
    }
}
