package me.projects.pushpage.oauth;

/** An OAuth protocol error, rendered as {@code {"error": ..., "error_description": ...}}. */
public class OAuthException extends RuntimeException {

    private final String error;
    private final int status;

    public OAuthException(String error, String description) {
        this(error, description, 400);
    }

    public OAuthException(String error, String description, int status) {
        super(description);
        this.error = error;
        this.status = status;
    }

    public String error() { return error; }

    public int status() { return status; }
}
