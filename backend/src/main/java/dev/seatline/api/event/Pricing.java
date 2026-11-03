package dev.seatline.api.event;

/**
 * All-in prices: what a fan sees on the seat map already includes fees and tax, so the total at
 * checkout is never a surprise. Everything is whole cents, rounded half up at each step.
 *
 * <pre>
 *   face      the tier's price
 *   discount  promo code percentage of face
 *   fee       fee_bps of (face - discount), plus a flat fee per ticket (none on free tickets)
 *   tax       tax_bps of (face - discount + fee)
 *   total     face - discount + fee + tax
 * </pre>
 */
public record Pricing(int feeBps, int feeFlatCents, int taxBps) {

    public record Line(long face, long discount, long fee, long tax, long total) {}

    public Line price(long faceCents, int percentOff) {
        long discount = share(faceCents, percentOff * 100L);
        long net = faceCents - discount;
        long fee = net == 0 ? 0 : share(net, feeBps) + feeFlatCents;
        long tax = share(net + fee, taxBps);
        return new Line(faceCents, discount, fee, tax, net + fee + tax);
    }

    /** {@code amount * bps / 10000}, rounded half up. */
    static long share(long amount, long bps) {
        return Math.floorDiv(amount * bps + 5_000, 10_000);
    }
}
