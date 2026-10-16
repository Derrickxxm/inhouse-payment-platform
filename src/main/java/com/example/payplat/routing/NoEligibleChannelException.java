package com.example.payplat.routing;

public class NoEligibleChannelException extends RuntimeException {
    public NoEligibleChannelException() {
        super("No channel accepted the submission");
    }
}
