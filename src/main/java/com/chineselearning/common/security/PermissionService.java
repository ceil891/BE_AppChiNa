package com.chineselearning.common.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.chineselearning.modules.user.repository.UserRepository;
import java.util.*;

@Service
public class PermissionService {
    private final JdbcClient jdbc;
    private final UserRepository users;
    public PermissionService(JdbcClient jdbc, UserRepository users) { this.jdbc=jdbc; this.users=users; }
    public List<String> forRole(String role) {
        return jdbc.sql("SELECT permission_code FROM role_permissions WHERE role_code=:role ORDER BY permission_code")
            .param("role",role).query(String.class).list();
    }
    public boolean has(String email,String permission) {
        return jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM users u JOIN role_permissions rp ON rp.role_code=u.role
            WHERE u.email=:email AND u.enabled AND rp.permission_code=:permission)
            """).param("email",email).param("permission",permission).query(Boolean.class).single();
    }
    public void require(String email,String permission) {
        if(!has(email,permission)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Bạn không có quyền thực hiện thao tác này.");
    }
    public void lockChanges() { jdbc.sql("SELECT id FROM authorization_lock WHERE id=1 FOR UPDATE").query(Integer.class).single(); }
    public String roleName(String role) { return jdbc.sql("SELECT name FROM roles WHERE code=:role").param("role",role).query(String.class).single(); }
    public boolean roleExists(String role) { return jdbc.sql("SELECT EXISTS(SELECT 1 FROM roles WHERE code=:role)").param("role",role).query(Boolean.class).single(); }
    public boolean canDelegate(String actor,String targetRole) {
        var user=users.findByEmail(actor).orElse(null);
        if(user==null || !user.enabled() || !roleExists(targetRole))return false;
        if("ADMIN".equals(user.role()))return true;
        return !"ADMIN".equals(targetRole) && new HashSet<>(forRole(user.role())).containsAll(forRole(targetRole));
    }
    public void requireDelegation(String actor,String role) {
        if(!roleExists(role))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Vai trò không tồn tại.");
        if(!canDelegate(actor,role))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Không thể quản lý tài khoản hoặc vai trò có quyền vượt quá quyền của bạn.");
    }
}
