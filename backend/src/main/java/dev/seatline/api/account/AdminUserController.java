package dev.seatline.api.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
class AdminUserController {

    record RoleRequest(@NotNull Role role) {}

    private final AccountService accounts;

    AdminUserController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PutMapping("/admin/users/{id}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setRole(@PathVariable UUID id, @Valid @RequestBody RoleRequest r) {
        accounts.setRole(id, r.role());
    }
}
