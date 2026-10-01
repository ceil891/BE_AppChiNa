package com.chineselearning.modules.admin.dto;

import jakarta.validation.constraints.*;
import java.util.Locale;

public final class AdminUserRequests {
    private AdminUserRequests() {}
    public record CreateUser(
        @NotBlank @Email @Size(max=254) String email,
        @NotBlank @Size(min=12,max=128,message="Mật khẩu cần 12–128 ký tự.") String password,
        @NotBlank @Size(max=80) String displayName, @NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{1,39}") String role) {
        public CreateUser { if (email != null) email = email.strip().toLowerCase(Locale.ROOT); }
    }
    public record UpdateUser(@NotBlank @Size(max=80) String displayName, @NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{1,39}") String role, @NotNull Boolean enabled) {}
    public record ResetPassword(@NotBlank @Size(min=12,max=128,message="Mật khẩu cần 12–128 ký tự.") String password) {}
    public record SetStatus(@NotNull Boolean enabled) {}
}
