package com.luigivismara.jev;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Anything that stopped a Jev call from producing a usable answer: a transport failure, a non-2xx
 * response, a response that did not match the expected decision schema, or an answer read as the
 * wrong type.
 *
 * <p>Unchecked, because there is rarely anything useful to do at the call site other than fall
 * back. Handle it where the fallback lives:
 *
 * <pre>{@code
 * try {
 *     JevResponse response = jev.ask(request);
 *     assignTo(response.choice("routing").choice());
 * } catch (JevException e) {
 *     switch (e.statusCode()) {
 *         case 401 -> throw new IllegalStateException("bad or revoked Jev key", e);
 *         case 422 -> {
 *             for (JevException.ValidationError detail : e.details()) {
 *                 log.error("invalid {}: {}", detail.loc(), detail.msg());
 *                 // invalid [body, questions, urgency, score, criteria]: Field required
 *             }
 *             throw e;   // a malformed request is a bug, not a blip
 *         }
 *         default -> log.error("Jev unavailable", e);
 *     }
 *     routeToManualReview();   // fail closed, never guess
 * }
 * }</pre>
 *
 * <p>{@link #statusCode()} is {@code 0} when the failure was not an HTTP response at all — a
 * timeout, a DNS failure, or a body that did not parse.
 */
public class JevException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * The HTTP status behind this failure, or {@code 0}.
     *
     * @serial
     */
    private final int statusCode;

    /** Validation details; transient because {@link ValidationError} carries arbitrary values. */
    private final transient List<ValidationError> details;

    /**
     * A failure with no HTTP response behind it.
     *
     * @param message what went wrong
     */
    public JevException(String message) {
        this(message, 0, List.of(), null);
    }

    /**
     * A failure with no HTTP response behind it, wrapping the underlying error.
     *
     * @param message what went wrong
     * @param cause   the underlying failure
     */
    public JevException(String message, Throwable cause) {
        this(message, 0, List.of(), cause);
    }

    /**
     * A failure carrying the HTTP status and any field-level validation errors.
     *
     * @param message    what went wrong
     * @param statusCode the HTTP status, or {@code 0} if there was no response
     * @param details    field-level validation errors, may be {@code null}
     * @param cause      the underlying failure, may be {@code null}
     */
    public JevException(String message, int statusCode, List<ValidationError> details,
                        Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    /**
     * The HTTP status that caused this, or {@code 0} if the failure was not an HTTP response.
     *
     * @return the status, or {@code 0}
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Field-level validation errors from a 422 response; empty otherwise.
     *
     * <p>The API points at the exact field it rejected, so log {@code loc()} rather than guessing
     * from the message:
     *
     * <pre>{@code
     * for (JevException.ValidationError detail : e.details()) {
     *     log.error("invalid {}: {}", detail.loc(), detail.msg());
     * }
     * }</pre>
     *
     * @return the validation errors, never {@code null}
     */
    public List<ValidationError> details() {
        return details;
    }

    /**
     * A request validation error at a specific field.
     *
     * @param loc path to the invalid value, e.g. {@code ["body", "questions", "urgency"]}
     * @param msg human-readable explanation
     * @param type machine-readable error code, e.g. {@code "missing"}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ValidationError(List<Object> loc, String msg, String type) {
    }
}
