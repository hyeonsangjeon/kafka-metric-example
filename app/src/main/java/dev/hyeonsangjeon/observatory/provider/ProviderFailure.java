package dev.hyeonsangjeon.observatory.provider;

public final class ProviderFailure extends RuntimeException {
    private final String safeCode;
    private final boolean throttled;

    public ProviderFailure(String safeCode, boolean throttled, Throwable cause) {
        super(safeCode, cause, false, false);
        this.safeCode = safeCode;
        this.throttled = throttled;
    }

    public String safeCode() {
        return safeCode;
    }

    public boolean throttled() {
        return throttled;
    }
}
