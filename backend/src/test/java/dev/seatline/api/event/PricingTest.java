package dev.seatline.api.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PricingTest {

    // 10% fee + $1.50 per ticket, 8% tax
    final Pricing pricing = new Pricing(1000, 150, 800);

    @Test
    void allInPriceIncludesFeesAndTax() {
        // $50 face: fee 5.00 + 1.50, tax 8% of 56.50 = 4.52
        assertThat(pricing.price(5000, 0)).isEqualTo(new Pricing.Line(5000, 0, 650, 452, 6102));
    }

    @Test
    void promoComesOffBeforeFeesAndTax() {
        // 20% off: net 40.00, fee 4.00 + 1.50, tax 8% of 45.50 = 3.64
        assertThat(pricing.price(5000, 20)).isEqualTo(new Pricing.Line(5000, 1000, 550, 364, 4914));
    }

    @Test
    void freeTicketsCostNothing() {
        assertThat(pricing.price(5000, 100).total()).isZero();
        assertThat(pricing.price(0, 0).total()).isZero();
    }

    @Test
    void roundsHalfUp() {
        assertThat(Pricing.share(5, 5000)).isEqualTo(3); // 2.5 -> 3
        assertThat(Pricing.share(5, 4999)).isEqualTo(2);
    }
}
