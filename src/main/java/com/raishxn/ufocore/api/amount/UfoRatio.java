package com.raishxn.ufocore.api.amount;

import java.math.BigInteger;
import java.util.Objects;

/** Exact non-negative rational used for rates, efficiencies and tiny values. */
public record UfoRatio(BigInteger numerator, BigInteger denominator) {
    public static final UfoRatio ZERO = new UfoRatio(BigInteger.ZERO, BigInteger.ONE);
    public static final UfoRatio ONE = new UfoRatio(BigInteger.ONE, BigInteger.ONE);

    public UfoRatio {
        Objects.requireNonNull(numerator, "numerator");
        Objects.requireNonNull(denominator, "denominator");
        if (numerator.signum() < 0) throw new IllegalArgumentException("numerator must be non-negative");
        if (denominator.signum() <= 0) throw new IllegalArgumentException("denominator must be positive");
        BigInteger divisor = numerator.gcd(denominator);
        numerator = numerator.divide(divisor);
        denominator = denominator.divide(divisor);
    }

    public static UfoRatio of(long numerator, long denominator) {
        return new UfoRatio(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    public UfoRatio multiply(UfoRatio other) {
        Objects.requireNonNull(other, "other");
        return new UfoRatio(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
    }

    public UfoAmount applyFloor(UfoAmount amount) {
        Objects.requireNonNull(amount, "amount");
        return UfoAmount.of(amount.asBigInteger().multiply(numerator).divide(denominator));
    }

    public UfoAmount applyCeil(UfoAmount amount) {
        Objects.requireNonNull(amount, "amount");
        BigInteger scaled = amount.asBigInteger().multiply(numerator);
        if (scaled.signum() == 0) return UfoAmount.ZERO;
        return UfoAmount.of(scaled.add(denominator).subtract(BigInteger.ONE).divide(denominator));
    }

    @Override
    public String toString() {
        return denominator.equals(BigInteger.ONE) ? numerator.toString() : numerator + "/" + denominator;
    }
}
