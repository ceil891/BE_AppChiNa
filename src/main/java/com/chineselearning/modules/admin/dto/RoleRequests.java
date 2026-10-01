package com.chineselearning.modules.admin.dto;
import jakarta.validation.constraints.*;
import java.util.List;

public final class RoleRequests {
    private RoleRequests() {}
    public record CreateRole(@NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{1,39}") String code,
        @NotBlank @Size(max=80) String name, @NotNull @Size(max=300) String description,
        @NotNull @Size(max=100) List<@NotBlank String> permissions) {}
    public record UpdateRole(@NotBlank @Size(max=80) String name, @NotNull @Size(max=300) String description,
        @NotNull @Size(max=100) List<@NotBlank String> permissions) {}
}
