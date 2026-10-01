package com.chineselearning.modules.admin.service;

import com.chineselearning.modules.admin.dto.AdminUserRequests.*;
import com.chineselearning.modules.admin.repository.AdminUserRepository;
import com.chineselearning.modules.admin.repository.AdminUserRepository.ManagedUser;
import com.chineselearning.modules.user.repository.UserRepository;
import com.chineselearning.common.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class AdminUserService {
    public record UserPage(List<ManagedUser> items,long total,int page,int size) {}
    private final AdminUserRepository repository;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final PermissionService access;
    public AdminUserService(AdminUserRepository repository,UserRepository users,PasswordEncoder encoder,PermissionService access) {
        this.repository=repository; this.users=users; this.encoder=encoder; this.access=access;
    }
    public UserPage list(String q,String role,String status,int page,int size) {
        if(q.length()>100 || (!role.isEmpty() && !access.roleExists(role)) || !Set.of("","active","locked").contains(status) || page<0 || size<1 || size>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Bộ lọc tài khoản không hợp lệ. Từ khóa tối đa 100 ký tự; kích thước trang 1–50.");
        return new UserPage(repository.list(q.strip(),role,status,page,size),repository.count(q.strip(),role,status),page,size);
    }
    public ManagedUser get(UUID id) { return repository.find(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy tài khoản.")); }
    private UUID lockAndCheckActor(String email,String permission) {
        access.lockChanges();
        access.require(email,permission);
        return users.findByEmail(email).filter(UserRepository.Account::enabled).map(UserRepository.Account::id)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN,"Bạn không còn quyền quản lý tài khoản."));
    }
    @Transactional public ManagedUser create(String actor,CreateUser input) {
        String hash=encoder.encode(input.password());
        lockAndCheckActor(actor,"users.create");
        if(!"USER".equals(input.role()))access.require(actor,"users.assign_role");
        access.requireDelegation(actor,input.role());
        try { return get(repository.create(input,hash)); }
        catch(DuplicateKeyException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Email đã được sử dụng."); }
    }
    @Transactional public ManagedUser update(String actor,UUID id,UpdateUser input) {
        access.lockChanges();
        if(!access.has(actor,"users.update") && !access.has(actor,"users.assign_role") && !access.has(actor,"users.status"))access.require(actor,"users.update");
        UUID actorId=users.findByEmail(actor).orElseThrow().id();
        var target=get(id);
        access.requireDelegation(actor,target.role());
        if(!target.displayName().equals(input.displayName().strip()))access.require(actor,"users.update");
        if(!target.role().equals(input.role()))access.require(actor,"users.assign_role");
        if(target.enabled()!=input.enabled())access.require(actor,"users.status");
        access.requireDelegation(actor,input.role());
        boolean removesAdmin=!"ADMIN".equals(input.role()) || !input.enabled();
        if(actorId.equals(id) && (!target.role().equals(input.role()) || !input.enabled())) throw conflict("Không thể tự đổi vai trò hoặc khóa tài khoản đang sử dụng.");
        protectLastAdmin(target,removesAdmin);
        repository.update(id,input);
        return get(id);
    }
    @Transactional public void resetPassword(String actor,UUID id,ResetPassword input) {
        String hash=encoder.encode(input.password());
        lockAndCheckActor(actor,"users.password"); access.requireDelegation(actor,get(id).role()); repository.resetPassword(id,hash);
    }
    @Transactional public ManagedUser status(String actor,UUID id,SetStatus input) {
        UUID actorId=lockAndCheckActor(actor,"users.status"); var target=get(id); access.requireDelegation(actor,target.role());
        if(actorId.equals(id) && !input.enabled()) throw conflict("Không thể khóa tài khoản đang sử dụng.");
        protectLastAdmin(target,!input.enabled());
        repository.update(id,new UpdateUser(target.displayName(),target.role(),input.enabled()));
        return get(id);
    }
    @Transactional public void delete(String actor,UUID id) {
        UUID actorId=lockAndCheckActor(actor,"users.delete"); var target=get(id); access.requireDelegation(actor,target.role());
        if(actorId.equals(id)) throw conflict("Không thể xóa tài khoản đang sử dụng.");
        protectLastAdmin(target,true); repository.delete(id);
    }
    private void protectLastAdmin(ManagedUser target,boolean removesAdmin) {
        if(removesAdmin && target.enabled() && "ADMIN".equals(target.role()) && repository.activeAdmins()<=1)
            throw conflict("Phải giữ ít nhất một quản trị viên đang hoạt động.");
    }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
