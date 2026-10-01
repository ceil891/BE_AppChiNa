package com.chineselearning.modules.progress.service;

import com.chineselearning.modules.progress.dto.ProgressDtos.Streak;
import java.time.LocalDate;
import java.util.*;

public final class StreakCalculator {
    private StreakCalculator() {}
    public static Streak calculate(Collection<LocalDate> dates, LocalDate today) {
        var days=new TreeSet<LocalDate>();
        dates.stream().filter(day -> !day.isAfter(today)).forEach(days::add);
        int longest=0,run=0;LocalDate previous=null;
        for (var day:days) { run=previous!=null && previous.plusDays(1).equals(day)?run+1:1;longest=Math.max(longest,run);previous=day; }
        int current=0;var cursor=days.contains(today)?today:today.minusDays(1);
        while (days.contains(cursor)) { current++;cursor=cursor.minusDays(1); }
        return new Streak(current,longest,days.size(),days.isEmpty()?null:days.last(),days.contains(today));
    }
}
