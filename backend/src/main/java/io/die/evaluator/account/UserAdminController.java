package io.die.evaluator.account;

import io.die.evaluator.account.AccountService.AccountChange;
import io.die.evaluator.auth.AppUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account administration. Admin-only in slice 2; class-scoped instructor access to students
 * arrives with class membership in slice 5.
 */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {

    public record CreateUserRequest(
        @NotBlank String role,
        @Size(max = 64) String studentNumber,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Size(max = 100) String firstName,
        @Size(max = 100) String middleName,
        @Email @Size(max = 254) String email) {}

    public record UpdateUserRequest(
        @NotNull Long version,
        String role,
        @Size(max = 64) String studentNumber,
        @Size(max = 100) String lastName,
        @Size(max = 100) String firstName,
        @Size(max = 100) String middleName,
        @Email @Size(max = 254) String email) {}

    private final AccountService accounts;

    public UserAdminController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public AccountRepository.PageResult list(@RequestParam(required = false) String role,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "25") int size) {
        return accounts.search(role, status, q, page, size);
    }

    @GetMapping("/{id}")
    public UserView get(@PathVariable UUID id) {
        return accounts.get(id);
    }

    @PostMapping
    public ResponseEntity<AccountChange> create(@Valid @RequestBody CreateUserRequest body,
                                                @AuthenticationPrincipal AppUserDetails actor) {
        AccountChange created = accounts.create(new AccountService.CreateUser(
            body.role(), body.studentNumber(), body.lastName(), body.firstName(), body.middleName(), body.email()),
            actor.getId());
        return ResponseEntity.created(URI.create("/api/v1/users/" + created.user().id()))
            .cacheControl(CacheControl.noStore()).body(created);
    }

    @PatchMapping("/{id}")
    public UserView update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest body,
                           @AuthenticationPrincipal AppUserDetails actor) {
        return accounts.update(id, new AccountService.UpdateUser(
            body.version(), body.role(), body.studentNumber(), body.lastName(), body.firstName(),
            body.middleName(), body.email()), actor.getId());
    }

    @PostMapping("/{id}/deactivate")
    public UserView deactivate(@PathVariable UUID id, @AuthenticationPrincipal AppUserDetails actor) {
        return accounts.deactivate(id, actor.getId());
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<AccountChange> activate(@PathVariable UUID id, @AuthenticationPrincipal AppUserDetails actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.activate(id, actor.getId()));
    }

    @PostMapping("/{id}/reset-credentials")
    public ResponseEntity<AccountChange> resetCredentials(@PathVariable UUID id,
                                                          @AuthenticationPrincipal AppUserDetails actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(accounts.resetCredentials(id, actor.getId()));
    }
}
