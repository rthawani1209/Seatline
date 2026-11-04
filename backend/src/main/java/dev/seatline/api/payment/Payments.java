package dev.seatline.api.payment;

/** Takes money. Charges are idempotent on the key, so a retried checkout never charges twice. */
public interface Payments {

    record Charge(boolean approved, String ref, String declineReason) {}

    /**
     * @param key idempotency key (the hold id): the same key always returns the first charge
     * @param token what the browser got from the card form
     */
    Charge charge(String key, long cents, String token, String description);

    void refund(String ref, long cents);
}
