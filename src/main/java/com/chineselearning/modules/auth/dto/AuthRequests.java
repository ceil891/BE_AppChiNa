package com.chineselearning.modules.auth.dto;

import jakarta.validation.constraints.*;
import java.util.Locale;

public final class AuthRequests {
    private AuthRequests() {}
    public record ChangePassword(
        @NotBlank(message="Nhập mật khẩu hiện tại.") @Size(max=128) String currentPassword,
        @NotBlank(message="Nhập mật khẩu mới.") @Size(min=12,max=128,message="Mật khẩu mới cần 12–128 ký tự.") String newPassword) {}
    public record Register(
        @NotBlank(message="Nhập email.") @Email(message="Email không hợp lệ.") @Size(max=254) String email,
        @NotBlank(message="Nhập mật khẩu.") @Size(min=12,max=128,message="Mật khẩu cần 12–128 ký tự.") String password,
        @NotBlank(message="Nhập tên hiển thị.") @Size(max=80,message="Tên tối đa 80 ký tự.") String displayName) {
        public Register { if (email != null) email = email.strip().toLowerCase(Locale.ROOT); }
    }
    public record Login(
        @NotBlank(message="Nhập email.") @Email(message="Email không hợp lệ.") @Size(max=254) String email,
        @NotBlank(message="Nhập mật khẩu.") @Size(max=128) String password) {
        public Login { if (email != null) email = email.strip().toLowerCase(Locale.ROOT); }
    }
}
