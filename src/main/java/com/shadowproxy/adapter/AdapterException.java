package com.shadowproxy.adapter;

/** Raised when an adapter cannot locate the content string in a provider's response. */
public class AdapterException extends RuntimeException {

    public AdapterException(String message) {
        super(message);
    }

    public AdapterException(String message, Throwable cause) {
        super(message, cause);
    }
}
