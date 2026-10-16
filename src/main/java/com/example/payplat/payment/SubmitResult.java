package com.example.payplat.payment;

// A component named "accepted" would collide with the required static accepted() factory.
public record SubmitResult(boolean isAccepted, String reason) {
    public static SubmitResult accepted() {
        return new SubmitResult(true, null);
    }

    public static SubmitResult rejected(String reason) {
        return new SubmitResult(false, reason);
    }
}
