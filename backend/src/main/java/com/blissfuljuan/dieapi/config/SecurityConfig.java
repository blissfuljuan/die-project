package com.blissfuljuan.dieapi.config;

import jakarta.servlet.DispatcherType;
import com.blissfuljuan.dieapi.account.repository.AccountRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Baseline security: server-managed sessions, CSRF via cookie, deny-by-default.
 * Login, activation and per-resource authorization arrive in P1; keep every new route
 * authenticated unless it is explicitly listed here.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, AccountRepository accounts, JsonAuthenticationEntryPoint entryPoint,
                                    JsonAccessDeniedHandler deniedHandler, CsrfTokenRepository csrfTokenRepository) throws Exception {
        http
            // Re-check account status/role on each authenticated request (see SessionRevalidationFilter).
            .addFilterBefore(new SessionRevalidationFilter(accounts), AuthorizationFilter.class)
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .headers(h -> h.frameOptions(f -> f.deny()))
            .authorizeHttpRequests(auth -> auth
                // The servlet container re-dispatches sendError() responses (CSRF failures, 403s) to /error.
                // Without this, anonymous callers got 401 for a CSRF failure: the real status was lost.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/status", "/api/v1/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/activation/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/password-reset/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * The double-submit cookie. Readable by the SPA (HttpOnly off, by design) but SameSite=Lax like the session cookie, and
     * replaced with a fresh value when the user signs in or out (see AuthController), so a token planted before sign-in
     * is worthless afterwards.
     */
    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
        return repository;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
