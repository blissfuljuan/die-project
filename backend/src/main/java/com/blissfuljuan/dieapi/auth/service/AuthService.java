package com.blissfuljuan.dieapi.auth.service;

import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import com.blissfuljuan.dieapi.audit.model.AuditResource;
import org.springframework.context.ApplicationEventPublisher;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

/**
 * Sign-in rules: throttling, credential check, audit. Session mechanics (cookie, fixation protection) are
 * web concerns and stay in the controller; everything that decides WHETHER someone may sign in is here.
 */
@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final LoginThrottleService throttle;
    private final ApplicationEventPublisher events;

    public AuthService(AuthenticationManager authenticationManager, LoginThrottleService throttle, ApplicationEventPublisher events) {
        this.authenticationManager = authenticationManager;
        this.throttle = throttle;
        this.events = events;
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
            events.publishEvent(AuditEvent.of(user.getId(), AuditAction.LOGIN_SUCCEEDED, AuditResource.USER, user.getId().toString(),
                Map.of("ip", String.valueOf(clientIp))));
            return authentication;
        } catch (AuthenticationException e) {
            throttle.record(identifier, clientIp, false);
            events.publishEvent(AuditEvent.of(null, AuditAction.LOGIN_FAILED, AuditResource.AUTH, null, Map.of("ip", String.valueOf(clientIp))));
            throw ApiException.unauthorized(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }
    }

    public void recordLogout(AppUserDetails user) {
        events.publishEvent(AuditEvent.of(user.getId(), AuditAction.LOGOUT, AuditResource.USER, user.getId().toString(), Map.of()));
    }
}
