package com.chineselearning.modules.auth.service;

import com.chineselearning.modules.auth.dto.AuthRequests.Register;
import com.chineselearning.modules.user.repository.UserRepository;
import com.chineselearning.modules.user.service.UserService.Profile;
import com.chineselearning.modules.user.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import com.chineselearning.modules.auth.dto.AuthRequests.ChangePassword;
import com.chineselearning.common.security.AccountPrincipal;
import com.chineselearning.common.exception.FieldValidationException;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final UserService profiles;
    public AuthService(UserRepository users, PasswordEncoder encoder, UserService profiles) { this.users = users; this.encoder = encoder; this.profiles=profiles; }
    public Profile register(Register body) {
        try {
            var u = users.create(body.email(), encoder.encode(body.password()), body.displayName().strip());
            return profiles.current(u.email());
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email đã được sử dụng.");
        }
    }
    @Transactional public void changePassword(AccountPrincipal principal,ChangePassword body) {
        var account=users.findByEmail(principal.getUsername()).filter(u->u.enabled() && u.id().equals(principal.accountId()) && u.sessionVersion()==principal.sessionVersion())
            .orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Phiên đăng nhập không còn hợp lệ."));
        if(!encoder.matches(body.currentPassword(),account.passwordHash()))throw new FieldValidationException("currentPassword","Mật khẩu hiện tại không đúng.");
        if(body.currentPassword().equals(body.newPassword()))throw new FieldValidationException("newPassword","Mật khẩu mới phải khác mật khẩu hiện tại.");
        if(!users.changePassword(account.id(),account.sessionVersion(),encoder.encode(body.newPassword())))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Tài khoản vừa thay đổi. Vui lòng đăng nhập lại trước khi đổi mật khẩu.");
    }
}
