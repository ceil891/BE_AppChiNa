package com.chineselearning.modules.admin.service;

import com.chineselearning.common.security.PermissionService;
import com.chineselearning.modules.admin.dto.RoleRequests.*;
import com.chineselearning.modules.user.repository.UserRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class AdminRoleService {
    public record Permission(String code,String name,String module) {}
    public record RoleRow(String code,String name,String description,boolean systemRole,long userCount) {}
    public record Role(String code,String name,String description,boolean systemRole,long userCount,List<String> permissions) {}
    private final JdbcClient jdbc;
    private final PermissionService access;
    private final UserRepository users;
    public AdminRoleService(JdbcClient jdbc,PermissionService access,UserRepository users){this.jdbc=jdbc;this.access=access;this.users=users;}
    public List<Permission> catalog(){return jdbc.sql("SELECT code,name,module FROM permissions ORDER BY module,code").query(Permission.class).list();}
    public List<Role> list(){return jdbc.sql("SELECT r.*,(SELECT COUNT(*) FROM users u WHERE u.role=r.code) AS user_count FROM roles r ORDER BY system_role DESC,code")
        .query(RoleRow.class).list().stream().map(this::details).toList();}
    public Role get(String code){return list().stream().filter(r->r.code().equals(code)).findFirst().orElseThrow(()->problem(HttpStatus.NOT_FOUND,"Không tìm thấy vai trò."));}
    public List<Role> options(String actor){return list().stream().filter(r->access.canDelegate(actor,r.code())).toList();}
    private Role details(RoleRow r){return new Role(r.code(),r.name(),r.description(),r.systemRole(),r.userCount(),access.forRole(r.code()));}
    private void lock(String actor,String permission){access.lockChanges();access.require(actor,permission);}
    private Set<String> validate(String actor,List<String> permissions) {
        var selected=new HashSet<>(permissions);
        var known=new HashSet<>(catalog().stream().map(Permission::code).toList());
        if(selected.size()!=permissions.size() || !known.containsAll(selected))throw problem(HttpStatus.BAD_REQUEST,"Danh sách quyền không hợp lệ hoặc bị trùng.");
        var account=users.findByEmail(actor).orElseThrow();
        if(!new HashSet<>(access.forRole(account.role())).containsAll(selected))throw problem(HttpStatus.FORBIDDEN,"Không thể cấp quyền mà bạn không có.");
        for(String permission:selected){
            String read=permission.substring(0,permission.indexOf('.'))+".read";
            if(!selected.contains(read))throw problem(HttpStatus.BAD_REQUEST,"Chọn quyền xem trước khi cấp các thao tác trong cùng mục.");
        }
        if((selected.contains("lessons.create") || selected.contains("lessons.update")) && !selected.contains("vocabulary.read"))
            throw problem(HttpStatus.BAD_REQUEST,"Biên tập bài học cần quyền xem từ vựng để chọn từ trong bài.");
        if((selected.contains("courses.create") || selected.contains("courses.update")) && !selected.contains("lessons.read"))
            throw problem(HttpStatus.BAD_REQUEST,"Biên tập khóa học cần quyền xem bài học.");
        return selected;
    }
    private void replacePermissions(String code,Set<String> permissions){
        jdbc.sql("DELETE FROM role_permissions WHERE role_code=:code").param("code",code).update();
        for(String permission:permissions)jdbc.sql("INSERT INTO role_permissions(role_code,permission_code) VALUES (:code,:permission)").param("code",code).param("permission",permission).update();
    }
    @Transactional public Role create(String actor,CreateRole input){
        lock(actor,"roles.create");var selected=validate(actor,input.permissions());
        if(access.roleExists(input.code()))throw problem(HttpStatus.CONFLICT,"Mã vai trò đã được sử dụng.");
        jdbc.sql("INSERT INTO roles(code,name,description) VALUES (:code,:name,:description)").param("code",input.code()).param("name",input.name().strip()).param("description",input.description().strip()).update();
        replacePermissions(input.code(),selected);return get(input.code());
    }
    @Transactional public Role update(String actor,String code,UpdateRole input){
        lock(actor,"roles.update");var role=get(code);editable(actor,role);var selected=validate(actor,input.permissions());
        jdbc.sql("UPDATE roles SET name=:name,description=:description WHERE code=:code").param("code",code).param("name",input.name().strip()).param("description",input.description().strip()).update();
        if(!new HashSet<>(role.permissions()).equals(selected)){
            replacePermissions(code,selected);
            jdbc.sql("UPDATE users SET session_version=session_version+1 WHERE role=:code").param("code",code).update();
        }
        return get(code);
    }
    @Transactional public void delete(String actor,String code){
        lock(actor,"roles.delete");var role=get(code);editable(actor,role);
        if(role.userCount()>0)throw problem(HttpStatus.CONFLICT,"Vai trò còn tài khoản sử dụng. Chuyển các tài khoản sang vai trò khác trước khi xóa.");
        jdbc.sql("DELETE FROM roles WHERE code=:code").param("code",code).update();
    }
    private void editable(String actor,Role role){
        if(role.systemRole())throw problem(HttpStatus.CONFLICT,"Không thể sửa hoặc xóa vai trò hệ thống.");
        access.requireDelegation(actor,role.code());
    }
    private ResponseStatusException problem(HttpStatus status,String detail){return new ResponseStatusException(status,detail);}
}
