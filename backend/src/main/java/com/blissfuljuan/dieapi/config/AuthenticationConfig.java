package com.blissfuljuan.dieapi.config;

import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.auth.service.AppUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Local-credential authentication. Future OIDC login adds another AuthenticationProvider
 * here without touching controllers or the session model.
 */
@Configuration
public class AuthenticationConfig {

    @Bean
    AuthenticationManager authenticationManager(AppUserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        // Runs AFTER the password check: a deactivated account costs the same as a wrong password.
        provider.setPostAuthenticationChecks(user -> {
            if (user instanceof AppUserDetails u && !u.isActive()) {
                throw new DisabledException("Account is not active");
            }
        });
        return new ProviderManager(provider);
    }
}
