package io.die.evaluator.auth;

import io.die.evaluator.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record LoginRequest(@NotBlank @Size(max = 254) String email,
                               @NotBlank @Size(max = 128) String password) {}

    public record MeResponse(UUID id, String email, String firstName, String lastName, String role) {
        static MeResponse of(AppUserDetails u) {
            return new MeResponse(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(), u.getRole());
        }
    }

    private final SecurityContextHolderStrategy contextStrategy = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    private final AuthenticationManager authenticationManager;
    private final LoginThrottleService throttle;
    private final AuditService audit;

    public AuthController(AuthenticationManager authenticationManager,
                          LoginThrottleService throttle,
                          AuditService audit) {
        this.authenticationManager = authenticationManager;
        this.throttle = throttle;
        this.audit = audit;
    }

    /** SPA calls this first; returning the token makes Spring set the XSRF-TOKEN cookie. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/login")
    public MeResponse login(@Valid @RequestBody LoginRequest body,
                            HttpServletRequest request, HttpServletResponse response) {
        String identifier = body.email().trim().toLowerCase(Locale.ROOT);
        String ip = request.getRemoteAddr();
        throttle.assertAllowed(identifier, ip);

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(identifier, body.password()));
        } catch (AuthenticationException e) {
            // Unknown user, bad password, disabled/deactivated: one indistinguishable response.
            throttle.record(identifier, ip, false);
            audit.record(null, "LOGIN_FAILED", "auth", null, Map.of("ip", String.valueOf(ip)));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        // Session fixation protection: always issue a new session id on login.
        request.getSession(true);
        request.changeSessionId();
        SecurityContext context = contextStrategy.createEmptyContext();
        context.setAuthentication(authentication);
        contextStrategy.setContext(context);
        contextRepository.saveContext(context, request, response);

        AppUserDetails user = (AppUserDetails) authentication.getPrincipal();
        throttle.record(identifier, ip, true);
        audit.record(user.getId(), "LOGIN_SUCCEEDED", "user", user.getId().toString(),
            Map.of("ip", String.valueOf(ip)));
        return MeResponse.of(user);
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AppUserDetails user) {
        return MeResponse.of(user);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal AppUserDetails user, Authentication authentication,
                       HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        audit.record(user.getId(), "LOGOUT", "user", user.getId().toString(), Map.of());
    }
}
