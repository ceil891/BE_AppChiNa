package com.chineselearning.modules.user.service;

import com.chineselearning.modules.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import java.util.List;
import com.chineselearning.common.security.PermissionService;

@Service
public class UserService {
    public record Profile(UUID id, String email, String displayName, String role, String roleName, List<String> permissions, String timeZone) {}
    private final UserRepository users;
    private final PermissionService access;
    public UserService(UserRepository users,PermissionService access) { this.users = users; this.access=access; }
    public Profile current(String email) {
        var u = users.findByEmail(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập không còn hợp lệ."));
        return new Profile(u.id(), u.email(), u.displayName(), u.role(),access.roleName(u.role()),access.forRole(u.role()),u.timeZone());
    }
    public Profile update(String email, String name) {
        var profile = current(email);
        users.updateName(profile.id(), name.strip());
        return current(email);
    }
}
