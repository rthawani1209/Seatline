package dev.seatline.api.security;

import dev.seatline.api.TestDatabase;
import dev.seatline.api.TestLedger;
import dev.seatline.api.account.Role;
import dev.seatline.api.account.User;
import dev.seatline.api.account.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Calls every route as nobody and as each role, and checks who gets in. The table below is the
 * access policy in one place; a route that isn't in it fails the test, so new endpoints can't ship
 * without someone deciding who may use them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabase.class, TestLedger.class})
class RouteAccessTest {

    static final Set<Role> NOBODY = EnumSet.noneOf(Role.class);
    static final Set<Role> SIGNED_IN = EnumSet.allOf(Role.class);

    /** Route to the roles allowed. {@code null} means public (no sign-in needed). */
    static final Map<String, Set<Role>> POLICY = new TreeMap<>();

    static {
        POLICY.put("POST /auth/register", null);
        POLICY.put("POST /auth/verify", null);
        POLICY.put("POST /auth/login", null);
        POLICY.put("POST /auth/forgot", null);
        POLICY.put("POST /auth/reset", null);
        POLICY.put("POST /me/password", SIGNED_IN);
        POLICY.put("POST /me/sign-out-everywhere", SIGNED_IN);
        POLICY.put("GET /me", SIGNED_IN);
        POLICY.put("POST /me/2fa/setup", SIGNED_IN);
        POLICY.put("POST /me/2fa/enable", SIGNED_IN);
        POLICY.put("POST /me/2fa/disable", SIGNED_IN);
        POLICY.put("PUT /admin/users/{id}/role", EnumSet.of(Role.ADMIN));
        POLICY.put("GET /admin/users", EnumSet.of(Role.ADMIN));
        POLICY.put("POST /venues", EnumSet.of(Role.ORGANIZER, Role.ADMIN));
        POLICY.put("GET /venues/{id}", null);
        POLICY.put("POST /events", EnumSet.of(Role.ORGANIZER, Role.ADMIN));
        POLICY.put("GET /events", null);
        POLICY.put("GET /events/{id}", null);
        POLICY.put("GET /events/{id}/seats", null);
        POLICY.put("GET /events/{id}/best-seats", null);
        POLICY.put("POST /events/{id}/publish", EnumSet.of(Role.ORGANIZER, Role.ADMIN));
        POLICY.put("POST /events/{id}/promo-codes", EnumSet.of(Role.ORGANIZER, Role.ADMIN));
        POLICY.put("GET /events/{id}/promo-codes", EnumSet.of(Role.ORGANIZER, Role.ADMIN));
        POLICY.put("POST /events/{id}/cancel", EnumSet.of(Role.ORGANIZER, Role.ADMIN));
        POLICY.put("POST /events/{id}/quote", SIGNED_IN);
        POLICY.put("POST /events/{id}/holds", SIGNED_IN);
        POLICY.put("DELETE /holds/{id}", SIGNED_IN);
        POLICY.put("POST /events/{id}/queue", SIGNED_IN);
        POLICY.put("GET /events/{id}/queue", SIGNED_IN);
        POLICY.put("POST /holds/{id}/checkout", SIGNED_IN);
        POLICY.put("GET /me/tickets", SIGNED_IN);
        POLICY.put("GET /me/tickets/{id}/qr", SIGNED_IN);
        POLICY.put("POST /scan", EnumSet.of(Role.DOOR_STAFF, Role.ADMIN));
        POLICY.put("GET /door/events", EnumSet.of(Role.DOOR_STAFF, Role.ADMIN));
        POLICY.put("GET /events/{id}/door-staff", EnumSet.of(Role.ORGANIZER, Role.ADMIN));
        POLICY.put("POST /events/{id}/door-staff", EnumSet.of(Role.ORGANIZER, Role.ADMIN));
        POLICY.put("DELETE /events/{id}/door-staff/{userId}", EnumSet.of(Role.ORGANIZER, Role.ADMIN));
        POLICY.put("POST /tickets/{id}/transfer", SIGNED_IN);
        POLICY.put("POST /tickets/{id}/return", SIGNED_IN);
        POLICY.put("GET /holds/{id}", SIGNED_IN);
        POLICY.put("GET /organizer/events", EnumSet.of(Role.ORGANIZER, Role.ADMIN));
        POLICY.put("GET /events/{id}/stats", EnumSet.of(Role.ORGANIZER, Role.ADMIN));
        POLICY.put("GET /admin/ledger", EnumSet.of(Role.ADMIN));
        POLICY.put("POST /admin/ledger/{id}/crash", EnumSet.of(Role.ADMIN));
        POLICY.put("POST /admin/ledger/{id}/revive", EnumSet.of(Role.ADMIN));
        POLICY.put("POST /admin/ledger/{id}/cut-off", EnumSet.of(Role.ADMIN));
        POLICY.put("POST /admin/ledger/heal", EnumSet.of(Role.ADMIN));
    }

    @Autowired MockMvc mvc;
    @Autowired TokenService tokens;
    @Autowired UserRepository users;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    @Test
    void everyRouteIsInThePolicy() {
        assertThat(routes()).isEqualTo(POLICY.keySet());
    }

    @Test
    void everyRoleGetsExactlyWhatThePolicySays() throws Exception {
        Map<Role, UUID> userFor = new TreeMap<>();
        for (Role role : Role.values()) {
            User u = users.save(new User(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com",
                    "unused", "Test " + role, role));
            userFor.put(role, u.id());
        }

        List<String> wrong = new ArrayList<>();
        for (var entry : POLICY.entrySet()) {
            String route = entry.getKey();
            Set<Role> allowed = entry.getValue();

            int anonymous = call(route, null);
            boolean anonymousAllowed = allowed == null;
            if (anonymousAllowed == (anonymous == 401 || anonymous == 403)) {
                wrong.add(route + " as nobody -> " + anonymous);
            }
            for (Role role : Role.values()) {
                // a fresh token each time: some routes (sign out everywhere) end the old ones
                int status = call(route, tokens.issue(users.findById(userFor.get(role)).orElseThrow()).value());
                boolean shouldGetIn = allowed == null || allowed.contains(role);
                boolean gotIn = status != 401 && status != 403;
                if (shouldGetIn != gotIn) {
                    wrong.add(route + " as " + role + " -> " + status);
                }
            }
        }
        assertThat(wrong).isEmpty();
    }

    private int call(String route, String token) throws Exception {
        String[] parts = route.split(" ", 2);
        String path = parts[1].replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
        MockHttpServletRequestBuilder req = request(HttpMethod.valueOf(parts[0]), path)
                .contentType(MediaType.APPLICATION_JSON).content("{}");
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    private Set<String> routes() {
        Set<String> out = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, method) -> {
            if (!method.getBeanType().getPackageName().startsWith("dev.seatline")) {
                return; // Spring's own /error
            }
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                for (var m : info.getMethodsCondition().getMethods()) {
                    out.add(m.name() + " " + pattern);
                }
            }
        });
        return out;
    }
}
