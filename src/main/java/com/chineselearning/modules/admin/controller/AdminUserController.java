package com.chineselearning.modules.admin.controller;

import com.chineselearning.modules.admin.dto.AdminUserRequests.*;
import com.chineselearning.modules.admin.repository.AdminUserRepository.ManagedUser;
import com.chineselearning.modules.admin.service.AdminUserService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {
    private final AdminUserService service;
    public AdminUserController(AdminUserService service) { this.service=service; }
    @GetMapping public AdminUserService.UserPage list(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String role,
            @RequestParam(defaultValue="") String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return service.list(q,role,status,page,size);
    }
    @GetMapping("/{id}") public ManagedUser get(@PathVariable UUID id) { return service.get(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ManagedUser create(Principal actor,@Valid @RequestBody CreateUser body) { return service.create(actor.getName(),body); }
    @PutMapping("/{id}") public ManagedUser update(Principal actor,@PathVariable UUID id,@Valid @RequestBody UpdateUser body) { return service.update(actor.getName(),id,body); }
    @PostMapping("/{id}/password") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(Principal actor,@PathVariable UUID id,@Valid @RequestBody ResetPassword body) { service.resetPassword(actor.getName(),id,body); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Principal actor,@PathVariable UUID id) { service.delete(actor.getName(),id); }
    @PatchMapping("/{id}/status") public ManagedUser status(Principal actor,@PathVariable UUID id,@Valid @RequestBody SetStatus body) { return service.status(actor.getName(),id,body); }
}
