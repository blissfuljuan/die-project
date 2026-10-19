package com.blissfuljuan.dieapi.auth.controller;

import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.auth.service.PasswordService;
import com.blissfuljuan.dieapi.auth.view.MessageView;
import com.blissfuljuan.dieapi.auth.view.StatusView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class PasswordController {

    public record ResetRequest(@NotBlank @Email @Size(max = 254) String email) {}

    public record ResetConfirmRequest(@NotBlank @Size(max = 128) String token,
                                      @NotBlank @Size(max = 128) String newPassword) {
        /** Never print secrets: records are logged by Spring at DEBUG ("Read ... to [Request[...]]"). */
        @Override
        public String toString() {
            return "ResetConfirmRequest[redacted]";
        }
    }

    public record ChangeRequest(@NotBlank @Size(max = 128) String currentPassword,
                                @NotBlank @Size(max = 128) String newPassword) {
        /** Never print secrets: records are logged by Spring at DEBUG ("Read ... to [Request[...]]"). */
        @Override
        public String toString() {
            return "ChangeRequest[redacted]";
        }
    }

    private final PasswordService passwords;

    public PasswordController(PasswordService passwords) {
        this.passwords = passwords;
    }

    /** Public. Same 202 whether or not the address has an account. */
    @PostMapping("/password-reset/request")
    public ResponseEntity<MessageView> request(@Valid @RequestBody ResetRequest body,
                                                       HttpServletRequest request) {
        passwords.requestReset(body.email(), request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
            .body(new MessageView("If an account exists for that address, a reset link has been sent."));
    }

    /** Public; the emailed token is the credential. */
    @PostMapping("/password-reset/confirm")
    public ResponseEntity<StatusView> confirm(@Valid @RequestBody ResetConfirmRequest body,
                                                       HttpServletRequest request) {
        passwords.confirmReset(body.token(), body.newPassword(), request.getRemoteAddr());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new StatusView("OK"));
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
