package dev.seatline.api.event;

import dev.seatline.api.account.AccountService;
import dev.seatline.api.web.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
class EventController {

    record TierRequest(@NotBlank @Size(max = 40) String name, @NotNull EventService.TierKind kind,
                       @Min(0) @Max(1_000_000) int priceCents, Instant endsAt,
                       @NotEmpty List<@NotBlank String> sections) {}

    record EventRequest(@NotNull UUID venueId, @NotBlank @Size(max = 120) String name, @NotNull @Future Instant startsAt,
                        @Min(1) @Max(20) int perFanLimit, @Min(0) @Max(3000) int feeBps,
                        @Min(0) @Max(2000) int feeFlatCents, @Min(0) @Max(2500) int taxBps,
                        @NotEmpty @Size(max = 10) List<@Valid TierRequest> tiers) {}

    record PromoRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9]{3,20}") String code,
                        @Min(1) @Max(100) int percentOff, @Min(1) int maxUses, Instant expiresAt) {}

    record QuoteRequest(@NotEmpty @Size(max = 20) List<@Valid Item> items, String promoCode) {
        record Item(@NotBlank String seat, @NotNull UUID tierId) {}
    }

    private final EventService events;
    private final AccountService accounts;

    EventController(EventService events, AccountService accounts) {
        this.events = events;
        this.accounts = accounts;
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EventRequest r) {
        List<EventService.TierSpec> tiers = r.tiers().stream()
                .map(t -> new EventService.TierSpec(t.name(), t.kind(), t.priceCents(), t.endsAt(), t.sections()))
                .toList();
        UUID id = events.create(CurrentUser.of(jwt), r.venueId(), r.name(), r.startsAt(), r.perFanLimit(),
                new Pricing(r.feeBps(), r.feeFlatCents(), r.taxBps()), tiers);
        return Map.of("id", id);
    }

    @GetMapping("/events")
    List<EventService.Event> list() {
        return events.listOnSale();
    }

    @GetMapping("/events/{id}")
    EventService.Event get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return events.getVisible(id, jwt == null ? null : CurrentUser.of(jwt));
    }

    @PostMapping("/events/{id}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void publish(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        events.publish(id, CurrentUser.of(jwt));
    }

    @PostMapping("/events/{id}/promo-codes")
    @ResponseStatus(HttpStatus.CREATED)
    void addPromo(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PromoRequest r) {
        events.addPromo(id, CurrentUser.of(jwt), r.code(), r.percentOff(), r.maxUses(), r.expiresAt());
    }

    @PostMapping("/events/{id}/quote")
    EventService.Quote quote(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody QuoteRequest r) {
        String email = accounts.get(CurrentUser.of(jwt).id()).email();
        List<EventService.QuoteItem> items = r.items().stream()
                .map(i -> new EventService.QuoteItem(i.seat(), i.tierId())).toList();
        return events.quote(id, items, r.promoCode(), email);
    }
}
