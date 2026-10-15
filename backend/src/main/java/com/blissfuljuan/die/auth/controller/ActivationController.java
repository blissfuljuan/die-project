package com.blissfuljuan.die.auth.controller;

import com.blissfuljuan.die.auth.service.ActivationService;
import com.blissfuljuan.die.auth.view.ActivationCheckView;
import com.blissfuljuan.die.auth.view.ActivationStatusView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public (unauthenticated, CSRF-protected) account activation endpoints. */
@RestController
@RequestMapping("/api/v1/auth/activation")
public class ActivationController {

    public record CheckRequest(@NotBlank @Size(max = 254) String identifier,
                               @NotBlank @Size(max = 64) String temporaryCredential) {}

    public record BeginRequest(@NotBlank @Size(max = 254) String identifier,
                               @NotBlank @Size(max = 64) String temporaryCredential,
                               @NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(max = 128) String newPassword) {}

    public record VerifyRequest(@NotBlank @Size(max = 128) String token) {}

    private final ActivationService activation;

    public ActivationController(ActivationService activation) {
        this.activation = activation;
    }

    @PostMapping("/check")
    public ResponseEntity<ActivationCheckView> check(@Valid @RequestBody CheckRequest body, HttpServletRequest request) {
        return noStore(activation.check(body.identifier(), body.temporaryCredential(), request.getRemoteAddr()));
    }

    @PostMapping("/begin")
    public ResponseEntity<ActivationStatusView> begin(@Valid @RequestBody BeginRequest body, HttpServletRequest request) {
        return noStore(activation.begin(body.identifier(), body.temporaryCredential(), body.email(),
            body.newPassword(), request.getRemoteAddr()));
    }

    @PostMapping("/verify")
    public ResponseEntity<ActivationStatusView> verify(@Valid @RequestBody VerifyRequest body) {
        return noStore(activation.verifyEmail(body.token()));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
