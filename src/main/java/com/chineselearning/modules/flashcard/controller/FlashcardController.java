package com.chineselearning.modules.flashcard.controller;
import com.chineselearning.modules.flashcard.dto.FlashcardDtos.*;
import com.chineselearning.modules.flashcard.service.FlashcardService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/flashcards")
public class FlashcardController {
    private final FlashcardService service;
    public FlashcardController(FlashcardService service){this.service=service;}
    @GetMapping public CardPage list(Principal p,@RequestParam(defaultValue="") String q,@RequestParam(required=false) Long lessonId,
        @RequestParam(defaultValue="false") boolean dueOnly,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.list(p.getName(),q,lessonId,dueOnly,page,size);}
    @GetMapping("/by-word/{id}") public CardLookup word(Principal p,@PathVariable long id){return service.byWord(p.getName(),id);}
    @GetMapping("/{id}") public Card get(Principal p,@PathVariable UUID id){return service.get(p.getName(),id);}
    @PostMapping public Card save(Principal p,@Valid @RequestBody SaveWord body){return service.save(p.getName(),body.vocabularyId());}
    @PostMapping("/lessons/{id}") public SavedLesson lesson(Principal p,@PathVariable long id){return service.saveLesson(p.getName(),id);}
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void archive(Principal p,@PathVariable UUID id){service.archive(p.getName(),id);}
    @PostMapping("/{id}/reviews") public Review review(Principal p,@PathVariable UUID id,@Valid @RequestBody SubmitReview body){return service.review(p.getName(),id,body);}
    @GetMapping("/reviews") public HistoryPage history(Principal p,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.history(p.getName(),page,size);}
}
