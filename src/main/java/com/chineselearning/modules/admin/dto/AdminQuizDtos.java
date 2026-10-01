package com.chineselearning.modules.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class AdminQuizDtos {
    private AdminQuizDtos() {}
    public record QuestionInput(
        @NotNull @Pattern(regexp="CHOOSE_MEANING|CHOOSE_WORD",message="Chọn loại câu hỏi hợp lệ.") String type,
        @NotBlank(message="Nhập nội dung câu hỏi.") @Size(max=500) String prompt,
        @NotNull @Size(min=4,max=4,message="Mỗi câu cần đúng bốn lựa chọn.") List<@NotBlank(message="Lựa chọn không được trống.") @Size(max=300) String> options,
        @NotNull @Min(0) @Max(3) Integer correctIndex,
        @NotBlank(message="Nhập giải thích đáp án.") @Size(max=1500) String explanation) {}
    public record Content(
        @NotBlank(message="Nhập tên bộ đề.") @Size(max=160) String title,
        @NotBlank(message="Nhập mô tả bộ đề.") @Size(max=500) String description,
        @Positive Long lessonId,
        @NotNull @Size(max=100,message="Một bộ đề tối đa 100 câu hỏi.") List<@NotNull @Valid QuestionInput> questions) {}
    public record Update(@NotNull @PositiveOrZero Long version,@NotNull @Valid Content content) {}
    public record Publication(@NotNull @PositiveOrZero Long version,@NotNull Boolean published) {}
    public record Summary(long id,String title,String description,Long lessonId,String lessonTitle,boolean published,long version,int questionCount) {}
    public record Question(long id,int position,String type,String prompt,List<String> options,int correctIndex,String explanation) {
        public QuestionInput input(){return new QuestionInput(type,prompt,options,correctIndex,explanation);}
    }
    public record Detail(Summary quiz,List<Question> questions) {}
    public record Page(List<Summary> items,long total,int page,int size) {}
}
