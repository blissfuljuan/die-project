package com.blissfuljuan.dieapi.auth.controller;

import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.auth.service.AuthService;
import com.blissfuljuan.dieapi.auth.view.CsrfTokenView;
import com.blissfuljuan.dieapi.auth.view.MeResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** Sign-in, sign-out and "who am I". Decisions live in AuthService; this class only does HTTP and session plumbing. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record LoginRequest(@NotBlank @Size(max = 254) String email,
                               @NotBlank @Size(max = 128) String password) {
        /** Never print secrets: records are logged by Spring at DEBUG ("Read ... to [Request[...]]"). */
        @Override
        public String toString() {
            return "LoginRequest[redacted]";
        }
    }

    private final SecurityContextHolderStrategy contextStrategy = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    /** SPA calls this first; returning the token makes Spring set the XSRF-TOKEN cookie. */
    @GetMapping("/csrf")
    public CsrfTokenView csrf(CsrfToken token) {
        return new CsrfTokenView(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/login")
    public MeResponse login(@Valid @RequestBody LoginRequest body,
                            HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = auth.authenticate(body.email(), body.password(), request.getRemoteAddr());

        // Session fixation protection: always issue a new session id on login.
        request.getSession(true);
        request.changeSessionId();
        SecurityContext context = contextStrategy.createEmptyContext();
        context.setAuthentication(authentication);
        contextStrategy.setContext(context);
        contextRepository.saveContext(context, request, response);

        return toView((AppUserDetails) authentication.getPrincipal());
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AppUserDetails user) {
        return toView(user);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal AppUserDetails user, Authentication authentication,
                       HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        auth.recordLogout(user);
    }

    private static MeResponse toView(AppUserDetails u) {
        return new MeResponse(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(), u.getRole());
    }
}
