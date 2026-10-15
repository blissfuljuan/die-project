package com.blissfuljuan.die.auth.service;

import com.blissfuljuan.die.audit.service.AuditService;
import com.blissfuljuan.die.auth.model.AppUserDetails;
import com.blissfuljuan.die.common.exception.ApiException;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

/**
 * Sign-in rules: throttling, credential check, audit. Session mechanics (cookie, fixation protection) are
 * web concerns and stay in the controller; everything that decides WHETHER someone may sign in is here.
 */
@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final LoginThrottleService throttle;
    private final AuditService audit;

    public AuthService(AuthenticationManager authenticationManager, LoginThrottleService throttle, AuditService audit) {
        this.authenticationManager = authenticationManager;
        this.throttle = throttle;
        this.audit = audit;
    }

    /**
     * @return the Authentication of the signed-in user
     * @throws ApiException 429 when throttled; 401 INVALID_CREDENTIALS for every other failure
     *         (unknown user, wrong password, deactivated account are deliberately indistinguishable)
     */
    public Authentication authenticate(String email, String password, String clientIp) {
        String identifier = email.trim().toLowerCase(Locale.ROOT);
        throttle.assertAllowed(identifier, clientIp);
        try {
            Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(identifier, password));
            AppUserDetails user = (AppUserDetails) authentication.getPrincipal();
            throttle.record(identifier, clientIp, true);
            audit.record(user.getId(), "LOGIN_SUCCEEDED", "user", user.getId().toString(),
                Map.of("ip", String.valueOf(clientIp)));
            return authentication;
        } catch (AuthenticationException e) {
            throttle.record(identifier, clientIp, false);
            audit.record(null, "LOGIN_FAILED", "auth", null, Map.of("ip", String.valueOf(clientIp)));
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password");
        }
    }

    public void recordLogout(AppUserDetails user) {
        audit.record(user.getId(), "LOGOUT", "user", user.getId().toString(), Map.of());
    }
}
