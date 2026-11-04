package dev.seatline.api.payment;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in payment provider for development, tests and the demo, until Stripe test keys are set.
 * Token {@code tok_visa} is approved and {@code tok_declined} is declined, like Stripe's test cards.
 */
@Component
public class FakePayments implements Payments {

    private final Map<String, Charge> charges = new ConcurrentHashMap<>();
    private final Map<String, Long> refunded = new ConcurrentHashMap<>();
    private final Map<String, Boolean> refundKeys = new ConcurrentHashMap<>();

    @Override
    public Charge charge(String key, long cents, String token, String description) {
        return charges.computeIfAbsent(key, k -> "tok_declined".equals(token)
                ? new Charge(false, null, "card_declined")
                : new Charge(true, "fake_" + UUID.randomUUID(), null));
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
