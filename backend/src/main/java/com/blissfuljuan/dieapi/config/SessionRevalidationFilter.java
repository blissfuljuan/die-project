package com.blissfuljuan.dieapi.config;

import com.blissfuljuan.dieapi.account.repository.AccountRepository;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Defence in depth for session revocation: on every authenticated request, re-read the account's
 * status and role and drop the session if the account is no longer active or its role changed since
 * sign-in. Explicit revocation (SessionRevocationService) remains the primary mechanism; this closes
 * the gap if that call ever fails or an account is changed outside the API.
 *
 * Deliberately NOT a Spring bean (a Filter bean would also be auto-registered in the servlet chain);
 * it is created and added in SecurityConfig. Cost: one primary-key lookup per authenticated request.
 */
public class SessionRevalidationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionRevalidationFilter.class);

    private final AccountRepository accounts;

    public SessionRevalidationFilter(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserDetails user && !stillValid(user)) {
            log.info("Dropping session of user {}: account inactive or role changed", user.getId());
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }
        chain.doFilter(request, response);
    }

    private boolean stillValid(AppUserDetails user) {
        return accounts.isActiveWithRole(user.getId(), user.getRole());
    }
}
