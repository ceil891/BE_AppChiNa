package com.chineselearning.modules.lesson.dto;

import java.util.List;

public record LessonDetail(LessonSummary lesson, List<Word> words) {}
