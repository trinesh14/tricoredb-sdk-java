package com.tricoredb;

/** No pooled connection became available within the caller's timeout. */
public class PoolTimeoutException extends TriCoreException {

    private static final long serialVersionUID = 1L;

    public PoolTimeoutException(String message) {
        super(message);
    }
}
