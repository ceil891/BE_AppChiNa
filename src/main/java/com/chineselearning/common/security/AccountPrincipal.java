package com.chineselearning.common.security;

import com.chineselearning.modules.user.repository.UserRepository.Account;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import java.util.UUID;

public final class AccountPrincipal extends User {
    private static final long serialVersionUID = 1L;
    private final UUID accountId;
    private final long sessionVersion;
    public AccountPrincipal(Account account) {
        super(account.email(), account.passwordHash(), account.enabled(), true, true, true,
            AuthorityUtils.createAuthorityList("ROLE_" + account.role()));
        accountId = account.id(); sessionVersion = account.sessionVersion();
    }
    public UUID accountId() { return accountId; }
    public long sessionVersion() { return sessionVersion; }
}
