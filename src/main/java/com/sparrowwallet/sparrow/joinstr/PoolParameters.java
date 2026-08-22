package com.sparrowwallet.sparrow.joinstr;

/**
 * The pool parameters a creator chooses. Both were hardcoded: every pool advertised a fee rate of
 * 1 sat/vB and expired one hour after creation.
 */
public final class PoolParameters {

    /** Matches the band a joiner will accept, so a pool cannot be created that nobody can join. */
    public static final double MIN_FEE_RATE = 1;
    public static final double MAX_FEE_RATE = 100;

    public static final long MIN_TIMEOUT_MINUTES = 5;
    public static final long MAX_TIMEOUT_MINUTES = 1440;

    public static final long DEFAULT_TIMEOUT_MINUTES = 60;

    private PoolParameters() {
    }

    /** Why this fee rate cannot be used, or null if it can. Blank means take the default. */
    public static String feeRateError(String text) {
        if (isBlank(text)) {
            return null;
        }

        double feeRate;
        try {
            feeRate = Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return "Fee rate must be a number.";
        }

        if (!Double.isFinite(feeRate) || feeRate < MIN_FEE_RATE || feeRate > MAX_FEE_RATE) {
            return "Fee rate must be between " + (long) MIN_FEE_RATE + " and " + (long) MAX_FEE_RATE
                    + " sat/vB.";
        }

        return null;
    }

    public static double feeRateOrDefault(String text, double fallback) {
        if (feeRateError(text) != null || isBlank(text)) {
            return fallback;
        }
        return Double.parseDouble(text.trim());
    }

    /** Why this timeout cannot be used, or null if it can. Blank means take the default. */
    public static String timeoutError(String text) {
        if (isBlank(text)) {
            return null;
        }

        long minutes;
        try {
            minutes = Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return "Timeout must be a whole number of minutes.";
        }

        if (minutes < MIN_TIMEOUT_MINUTES || minutes > MAX_TIMEOUT_MINUTES) {
            return "Timeout must be between " + MIN_TIMEOUT_MINUTES + " and " + MAX_TIMEOUT_MINUTES
                    + " minutes.";
        }

        return null;
    }

    public static long timeoutSeconds(String text, long fallbackMinutes) {
        long minutes = fallbackMinutes;
        if (timeoutError(text) == null && !isBlank(text)) {
            minutes = Long.parseLong(text.trim());
        }
        return minutes * 60;
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
