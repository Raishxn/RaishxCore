package com.raishxn.ufocore.headtohead;

import java.math.BigInteger;
import java.util.Map;

/**
 * A planner under test. {@link #prepare} lowers the neutral case into the engine's own graph model
 * outside the timed region, because both production integrations build and cache that graph per grid
 * revision; {@link Prepared#run} is the planning call that is timed.
 */
interface Engine {

    String name();

    /** @throws Unsupported when the engine's model cannot express the case */
    Prepared prepare(BenchCase benchCase);

    interface Prepared {
        Result run();
    }

    final class Unsupported extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unsupported(String message) {
            super(message);
        }
    }

    enum Status {
        /** A complete plan: nothing reported missing. */
        COMPLETE,
        /** A plan with a reported shortage. */
        SHORTAGE,
        /** The engine stopped on its own budget, depth or deadline without a usable plan. */
        LIMIT,
        /** The engine (or its adapter) cannot express the case. */
        UNSUPPORTED,
        /** The harness deadline expired before the engine returned. */
        TIMEOUT,
        ERROR
    }

    /**
     * What one planning call reported.
     *
     * @param firings pattern id to executions
     * @param budgetHit the engine said it ran out of search budget, even if it returned a plan
     */
    record Result(Status status, Map<String, BigInteger> firings, Map<String, BigInteger> missing,
                  boolean budgetHit, String note) {

        BigInteger missingTotal() {
            return missing.values().stream().reduce(BigInteger.ZERO, BigInteger::add);
        }

        BigInteger executions() {
            return firings.values().stream().reduce(BigInteger.ZERO, BigInteger::add);
        }

        boolean planned() {
            return status == Status.COMPLETE || status == Status.SHORTAGE;
        }

        static Result failed(Status status, String note) {
            return new Result(status, Map.of(), Map.of(), false, note);
        }
    }
}
