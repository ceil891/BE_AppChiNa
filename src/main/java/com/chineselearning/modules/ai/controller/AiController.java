package com.chineselearning.modules.ai.controller;

import com.chineselearning.modules.ai.dto.AiDtos.*;
import com.chineselearning.modules.ai.service.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {
    private final AiTransactions transactions;private final AiTutorService tutor;
    public AiController(AiTransactions transactions,AiTutorService tutor) { this.transactions=transactions;this.tutor=tutor; }
    @GetMapping("/status") public Status status(Principal p) { return transactions.status(p.getName()); }
    @GetMapping("/conversations") public Page list(Principal p,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return transactions.list(p.getName(),page,size); }
    @PostMapping("/conversations") public Detail start(Principal p,@Valid @RequestBody Start input) { return transactions.start(p.getName(),input); }
    @GetMapping("/conversations/{id}") public Detail get(Principal p,@PathVariable UUID id) { return transactions.get(p.getName(),id); }
    @PatchMapping("/conversations/{id}") public Detail rename(Principal p,@PathVariable UUID id,@Valid @RequestBody Rename input) { return transactions.rename(p.getName(),id,input); }
    @DeleteMapping("/conversations/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(Principal p,@PathVariable UUID id) { transactions.delete(p.getName(),id); }
    @PostMapping("/conversations/{id}/turns") public Turn send(Principal p,@PathVariable UUID id,@Valid @RequestBody Send input) { return tutor.send(p.getName(),id,input); }
}
