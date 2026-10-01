package com.chineselearning.modules.foundation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;

public final class FoundationModels {
    private FoundationModels() {}
    public record Section(@NotBlank @Size(max=160) String title,@NotBlank @Size(max=5000) String text) {}
    public record Group(@NotBlank @Size(max=160) String title,@NotBlank @Size(max=500) String sounds,@NotBlank @Size(max=1000) String note) {}
    public record Example(@NotBlank @Size(max=80) String symbol,@NotBlank @Size(max=200) String pinyin,
        @NotBlank @Size(max=200) String hanzi,@NotBlank @Size(max=300) String meaning,
        @NotBlank @Size(max=1000) String tip,@NotBlank @Size(max=500) String audioSrc) {}
    public record Question(@NotBlank @Size(max=500) String prompt,
        @NotNull @Size(min=2,max=6) List<@NotBlank @Size(max=500) String> options,
        @NotNull @Min(0) @Max(5) Integer correct,@NotBlank @Size(max=1500) String explanation) {}
    public record Content(@NotBlank @Pattern(regexp="[a-z][a-z0-9-]{1,59}") String slug,
        @NotBlank @Size(max=160) String title,@NotBlank @Size(max=500) String subtitle,
        @Min(1) @Max(120) int minutes,@NotBlank @Size(max=20) String symbol,
        @NotNull @Size(min=1,max=20) List<@NotNull @Valid Section> sections,
        @NotNull @Size(max=20) List<@NotNull @Valid Group> groups,
        @NotNull @Size(min=1,max=30) List<@NotNull @Valid Example> examples,
        @NotNull @Size(min=1,max=20) List<@NotNull @Valid Question> questions,boolean toneChart) {}
    public record Input(@NotNull @Valid Content content,@Min(1) @Max(10000) int position,@NotNull @PositiveOrZero Long version) {}
    public record Publication(@NotNull @PositiveOrZero Long version,@NotNull Boolean published) {}
    public record Detail(Content content,int position,boolean published,long version) {}
    public record Card(String slug,String title,String subtitle,int minutes,String symbol,int position,long version,
        boolean locked,String prerequisiteSlug,String prerequisiteTitle,boolean completed) {}
    public record PublicQuestion(String prompt,List<String> options) {}
    public record Lesson(String slug,String title,String subtitle,int minutes,String symbol,List<Section> sections,
        List<Group> groups,List<Example> examples,List<PublicQuestion> questions,boolean toneChart,long version) {}
    public record Submit(@NotNull @PositiveOrZero Long lessonVersion,@NotNull UUID requestId,
        @NotNull @Size(min=1,max=20) List<@NotNull @Min(0) @Max(5) Integer> answers) {}
    public record Review(String prompt,List<String> options,int selected,int correct,String explanation) {}
    public record Result(UUID requestId,String slug,String title,long lessonVersion,int correctCount,int total,int score,
        OffsetDateTime submittedAt,boolean saved,List<Review> questions) {}
    public record Bookmark(@NotNull @PositiveOrZero Long lessonVersion,@Min(0) @Max(19) int sectionIndex,@NotNull @PositiveOrZero Long version) {}
    public record Progress(String slug,int sectionIndex,long lessonVersion,long version,Integer bestScore,Integer lastScore,
        int attempts,OffsetDateTime completedAt,OffsetDateTime updatedAt,Result lastResult) {}
}
