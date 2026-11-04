package dev.seatline.api.order;

import dev.seatline.api.event.EventService;
import dev.seatline.api.web.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
class OrderController {

    record Item(@NotBlank String seat, @NotNull UUID tierId) {}

    record HoldRequest(@NotEmpty @Size(max = 20) List<@Valid Item> items, String promoCode) {}

    record CheckoutRequest(@NotBlank @Size(max = 200) String cardToken) {}

    record TransferRequest(@NotBlank @Email String toEmail) {}

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping("/events/{id}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    OrderService.Hold hold(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody HoldRequest r) {
        List<EventService.QuoteItem> items = r.items().stream().map(i -> new EventService.QuoteItem(i.seat(), i.tierId())).toList();
        return orders.hold(id, CurrentUser.of(jwt), items, r.promoCode());
    }

    @DeleteMapping("/holds/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void release(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        orders.release(id, CurrentUser.of(jwt));
    }

    @PostMapping("/holds/{id}/checkout")
    OrderService.Order checkout(@PathVariable String id, @AuthenticationPrincipal Jwt jwt,
                                @Valid @RequestBody CheckoutRequest r) {
        return orders.checkout(id, CurrentUser.of(jwt), r.cardToken());
    }

    @GetMapping("/me/tickets")
    List<OrderService.TicketView> tickets(@AuthenticationPrincipal Jwt jwt) {
        return orders.tickets(CurrentUser.of(jwt));
    }

    @PostMapping("/tickets/{id}/transfer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void transfer(@PathVariable String id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TransferRequest r) {
        orders.transfer(id, CurrentUser.of(jwt), r.toEmail());
    }

    @PostMapping("/tickets/{id}/return")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void giveBack(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        orders.giveBack(id, CurrentUser.of(jwt));
    }
}
