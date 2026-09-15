package io.github.trinesh14.tricoredb;

/** Authentication was refused (bad credentials, or an {@code AUTH_OK} frame with {@code ok: false}). */
public class AuthException extends TriCoreException {

    private static final long serialVersionUID = 1L;

    public AuthException(String message) {
        super(message);
    }
}
