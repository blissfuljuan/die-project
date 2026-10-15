package io.die.evaluator.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class PasswordController {

    public record ResetRequest(@NotBlank @Email @Size(max = 254) String email) {}

    public record ResetConfirmRequest(@NotBlank @Size(max = 128) String token,
                                      @NotBlank @Size(max = 128) String newPassword) {}

    public record ChangeRequest(@NotBlank @Size(max = 128) String currentPassword,
                                @NotBlank @Size(max = 128) String newPassword) {}

    private final PasswordService passwords;

    public PasswordController(PasswordService passwords) {
        this.passwords = passwords;
    }

    /** Public. Same 202 whether or not the address has an account. */
    @PostMapping("/password-reset/request")
    public ResponseEntity<Map<String, String>> request(@Valid @RequestBody ResetRequest body,
                                                       HttpServletRequest request) {
        passwords.requestReset(body.email(), request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
            .body(Map.of("message", "If an account exists for that address, a reset link has been sent."));
    }

    /** Public; the emailed token is the credential. */
    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Map<String, String>> confirm(@Valid @RequestBody ResetConfirmRequest body,
                                                       HttpServletRequest request) {
        passwords.confirmReset(body.token(), body.newPassword(), request.getRemoteAddr());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("status", "OK"));
    }

    /** Authenticated (any role). */
    @PostMapping("/password/change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void change(@AuthenticationPrincipal AppUserDetails user, @Valid @RequestBody ChangeRequest body,
                       HttpServletRequest request) {
        passwords.change(user.getId(), request.getSession().getId(), body.currentPassword(),
            body.newPassword(), request.getRemoteAddr());
    }
}
