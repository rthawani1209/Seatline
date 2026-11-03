package dev.seatline.api;

import dev.seatline.api.account.Role;
import dev.seatline.api.account.User;
import dev.seatline.api.account.UserRepository;
import dev.seatline.api.security.TokenService;
import org.springframework.boot.test.context.TestComponent;

import java.util.UUID;

/** Makes users straight in the database and hands back an access token for them. */
@TestComponent
public class TestUsers {

    private final UserRepository users;
    private final TokenService tokens;

    public TestUsers(UserRepository users, TokenService tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    /** Returns "Bearer ..." for a new user with this role. */
    public String bearer(Role role) {
        return bearer(role, role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com");
    }

    public String bearer(Role role, String email) {
        User u = users.save(new User(email, "unused", "Test " + role, role));
        return "Bearer " + tokens.issue(u).value();
    }
}
