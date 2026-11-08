package dev.seatline.api.event;

import dev.seatline.api.account.AccountService;
import dev.seatline.api.web.CurrentUser;
import dev.seatline.api.web.RateLimiter;
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
import org.springframework.web.bind.annotation.RequestParam;
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
                        @Min(1) @Max(100_000) Integer queuePerMinute, @Future Instant onSaleAt,
                        @NotEmpty @Size(max = 10) List<@Valid TierRequest> tiers) {}

    record PromoRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9]{3,20}") String code,
                        @Min(1) @Max(100) int percentOff, @Min(1) @Max(1_000_000) int maxUses,
                        @Future Instant expiresAt) {}

    record QuoteRequest(@NotEmpty @Size(max = 20) List<@Valid Item> items, String promoCode) {
        record Item(@NotBlank String seat, @NotNull UUID tierId) {}
    }

    private final EventService events;
    private final AccountService accounts;
    private final BestSeats bestSeats;
    private final RateLimiter limiter;

    EventController(EventService events, AccountService accounts, BestSeats bestSeats, RateLimiter limiter) {
        this.events = events;
        this.accounts = accounts;
        this.bestSeats = bestSeats;
        this.limiter = limiter;
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EventRequest r) {
        List<EventService.TierSpec> tiers = r.tiers().stream()
                .map(t -> new EventService.TierSpec(t.name(), t.kind(), t.priceCents(), t.endsAt(), t.sections()))
                .toList();
        UUID id = events.create(CurrentUser.of(jwt), r.venueId(), r.name(), r.startsAt(), r.perFanLimit(),
                new Pricing(r.feeBps(), r.feeFlatCents(), r.taxBps()), r.queuePerMinute(), r.onSaleAt(), tiers);
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

    /** Suggests the best free seats together, for the "best available" button. */
    @GetMapping("/events/{id}/best-seats")
    Map<String, List<String>> bestSeats(@PathVariable UUID id, @RequestParam UUID tierId,
                                        @RequestParam(defaultValue = "2") int count,
                                        @RequestParam(defaultValue = "false") boolean accessible) {
        return Map.of("seats", bestSeats.pick(id, tierId, count, accessible));
    }

    /** Free, held or sold for each seat. Only statuses: who holds or owns a seat stays private. */
    @GetMapping("/events/{id}/seats")
    List<EventService.SeatState> seats(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return events.seatStates(id, jwt == null ? null : CurrentUser.of(jwt));
    }

    @PostMapping("/events/{id}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void publish(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        events.publish(id, CurrentUser.of(jwt));
    }

    @GetMapping("/events/{id}/promo-codes")
    List<EventService.Promo> promos(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return events.promos(id, CurrentUser.of(jwt));
    }

    @PostMapping("/events/{id}/promo-codes")
    @ResponseStatus(HttpStatus.CREATED)
    void addPromo(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PromoRequest r) {
        events.addPromo(id, CurrentUser.of(jwt), r.code(), r.percentOff(), r.maxUses(), r.expiresAt());
    }

    /**
     * Fans quote as they pick seats, so a person never gets near this. A script trying promo codes
     * one after another does.
     */
    static final int QUOTES_PER_MINUTE = 60;

    @PostMapping("/events/{id}/quote")
    EventService.Quote quote(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody QuoteRequest r) {
        limiter.spend("quote:" + jwt.getSubject(), QUOTES_PER_MINUTE);
        String email = accounts.get(CurrentUser.of(jwt).id()).email();
        List<EventService.QuoteItem> items = r.items().stream()
                .map(i -> new EventService.QuoteItem(i.seat(), i.tierId())).toList();
        return events.quote(id, items, r.promoCode(), email);
    }
}
