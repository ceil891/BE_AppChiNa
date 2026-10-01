package com.chineselearning.modules.lesson.dto;

public record LessonSummary(long id, String title, String description, int position, int wordCount, boolean published) {}
