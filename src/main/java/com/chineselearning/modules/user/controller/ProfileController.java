package com.chineselearning.modules.user.controller;

import com.chineselearning.modules.user.service.UserService;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {
    public record UpdateProfile(@NotBlank(message="Tên không được để trống.") @Size(max=80, message="Tên tối đa 80 ký tự.") String displayName) {}
    private final UserService users;
    public ProfileController(UserService users) { this.users = users; }
    @GetMapping public UserService.Profile get(Principal principal) { return users.current(principal.getName()); }
    @PatchMapping public UserService.Profile update(Principal principal, @Valid @RequestBody UpdateProfile body) {
        return users.update(principal.getName(), body.displayName());
    }
}

