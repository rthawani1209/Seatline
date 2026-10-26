package dev.seatline.ledger.check;

/**
 * A sequential specification: what a correct single-copy system would do.
 *
 * @param <S> state; must have value equality (equals/hashCode), the checker caches on it
 */
public interface Model<S> {

    S initial();

    /**
     * Applies {@code input} to {@code state}. Returns the new state if the system could have
     * produced {@code output} here, or null if not. A null {@code output} means the outcome is
     * unknown, so any output is acceptable.
     */
    S step(S state, Object input, Object output);
}
