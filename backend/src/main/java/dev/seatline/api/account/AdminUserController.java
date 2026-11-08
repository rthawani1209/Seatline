package dev.seatline.api.account;

import dev.seatline.api.web.ApiException;
import dev.seatline.api.web.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
class AdminUserController {

    record RoleRequest(@NotNull Role role) {}

    record UserRow(UUID id, String email, String displayName, Role role, boolean emailVerified) {}

    private final AccountService accounts;
    private final JdbcClient db;

    AdminUserController(AccountService accounts, JdbcClient db) {
        this.accounts = accounts;
        this.db = db;
    }

    /** Accounts whose email contains the search text (at most 20), for the admin page. */
    @GetMapping("/admin/users")
    List<UserRow> find(@RequestParam(defaultValue = "") String email) {
        String like = "%" + email.strip().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return db.sql("""
                select id, email, display_name, role, email_verified from users
                where email like ? order by email limit 20""").param(like)
                .query((rs, n) -> new UserRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        Role.valueOf(rs.getString(4)), rs.getBoolean(5)))
                .list();
    }

    @PutMapping("/admin/users/{id}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setRole(@PathVariable UUID id, @Valid @RequestBody RoleRequest r, @AuthenticationPrincipal Jwt jwt) {
        // an admin demoting themselves could leave nobody able to undo it
        if (id.equals(CurrentUser.of(jwt).id())) {
            throw new ApiException(HttpStatus.CONFLICT, "cant_change_own_role");
        }
        accounts.setRole(id, r.role());
    }
}
