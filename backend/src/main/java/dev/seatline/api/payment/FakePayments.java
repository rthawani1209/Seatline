package dev.seatline.api.payment;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in payment provider for development, tests and the demo, until Stripe test keys are set.
 * It only knows Stripe's documented test tokens: the approving ones go through, {@code tok_declined}
 * and anything it doesn't recognise are declined, so nothing pretends to be a real card.
 */
@Component
public class FakePayments implements Payments {

    static final Set<String> APPROVED = Set.of("tok_visa", "tok_mastercard", "tok_amex");

    private final Map<String, Charge> charges = new ConcurrentHashMap<>();
    private final Map<String, Long> refunded = new ConcurrentHashMap<>();
    private final Map<String, Boolean> refundKeys = new ConcurrentHashMap<>();

    @Override
    public Charge charge(String key, long cents, String token, String description) {
        return charges.computeIfAbsent(key, k -> APPROVED.contains(token)
                ? new Charge(true, "fake_" + UUID.randomUUID(), null)
                : new Charge(false, null, "tok_declined".equals(token) ? "card_declined" : "test_cards_only"));
    }

    @Override
    public void refund(String key, String ref, long cents) {
        if (refundKeys.putIfAbsent(key, true) == null) {
            refunded.merge(ref, cents, Long::sum);
        }
    }

    /** Total refunded on a charge, for tests. */
    public long refundedOn(String ref) {
        return refunded.getOrDefault(ref, 0L);
    }
}
