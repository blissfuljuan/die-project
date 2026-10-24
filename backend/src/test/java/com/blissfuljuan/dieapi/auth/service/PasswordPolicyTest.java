package com.blissfuljuan.dieapi.auth.service;

import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy(new CommonPasswords());

    private void assertTooEasy(String password) {
        assertThatThrownBy(() -> policy.validate(password, List.of()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("too easy"));
    }

    @Test
    void refusesListedPasswordsIgnoringCaseAndPunctuation() {
        assertTooEasy("password1234");
        assertTooEasy("Password-1234");
        assertTooEasy("QWERTYUIOP 123");
    }

    @Test
    void refusesRepeatedCharactersAndPlainRuns() {
        assertTooEasy("aaaaaaaaaaaa");
        assertTooEasy("111111111111");
        assertTooEasy("123456789012");
        assertTooEasy("abcdefghijkl");
    }

    @Test
    void acceptsOrdinaryPassphrases() {
        assertThatCode(() -> policy.validate("a brand new passphrase", List.of())).doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("correct horse battery staple", List.of())).doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("Sup3r-Secret-Login-Pw!", List.of())).doesNotThrowAnyException();
    }

    @Test
    void stillEnforcesLengthAndIdentifierRules() {
        assertThatThrownBy(() -> policy.validate("short", List.of())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.validate("someone@example.edu", List.of("someone@example.edu"))).isInstanceOf(ApiException.class);
    }

    @Test
    void usesTheDedicatedErrorCode() {
        assertThatThrownBy(() -> policy.validate("password1234", List.of()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PASSWORD_TOO_EASY.name()));
    }
}
