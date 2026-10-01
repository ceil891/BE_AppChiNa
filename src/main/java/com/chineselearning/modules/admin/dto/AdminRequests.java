package com.chineselearning.modules.admin.dto;
import jakarta.validation.constraints.*;
import java.util.List;

public final class AdminRequests {
    private AdminRequests() {}
    public record VocabularyInput(
        @NotBlank @Size(max=80) String hanzi,
        @NotBlank @Size(max=160) String pinyin,
        @NotBlank @Size(max=300) String meaningVi,
        @NotBlank @Size(max=300) String exampleHanzi,
        @NotBlank @Size(max=500) String examplePinyin,
        @NotBlank @Size(max=500) String exampleMeaningVi) {}
    public record LessonInput(
        @NotBlank @Size(max=160) String title,
        @NotBlank @Size(max=500) String description,
        @NotNull @Positive Integer position,
        @NotNull @Size(max=200) List<@NotNull @Positive Long> wordIds) {}
}

