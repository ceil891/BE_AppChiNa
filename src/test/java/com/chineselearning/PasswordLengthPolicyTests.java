package com.chineselearning;

import com.chineselearning.modules.auth.dto.AuthRequests;
import com.chineselearning.modules.admin.dto.AdminUserRequests;
import com.chineselearning.modules.auth.controller.AccountSecurityController;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PasswordLengthPolicyTests {
    @Test void allPasswordCreationFlowsAcceptEightTo128CharactersOnly() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            for(int length: new int[]{7,8,128,129}) {
                String password="a".repeat(length);
                var requests=List.of(
                    new AuthRequests.Register("learner@example.test",password,"Learner"),
                    new AuthRequests.ChangePassword("old-password",password),
                    new AdminUserRequests.CreateUser("learner@example.test",password,"Learner","USER"),
                    new AdminUserRequests.ResetPassword(password),
                    new AccountSecurityController.Reset("a".repeat(43),password));
                for(var request:requests) {
                    assertThat(validator.validate(request).isEmpty())
                        .as("%s with %s characters",request.getClass().getSimpleName(),length)
                        .isEqualTo(length>=8 && length<=128);
                }
            }
        }
    }
}
