package com.blissfuljuan.dieapi.auth.service;

import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.auth.repository.LoginUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Loads a user by login identifier. Slice 1: verified email only.
 * Student-ID login for PENDING_ACTIVATION accounts is added in slice 3 (activation).
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final LoginUserRepository users;

    public AppUserDetailsService(LoginUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        String email = identifier.trim().toLowerCase(Locale.ROOT);
        return users.findLoginByEmail(email)
            .map(u -> (UserDetails) u)
            .orElseThrow(() -> new UsernameNotFoundException("unknown"));
    }
}
