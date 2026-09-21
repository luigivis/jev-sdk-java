package com.luigivismara.jev;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The three question primitives Jev understands. This set is fixed by the API, which is why it is
 * an enum rather than a string.
 *
 * <p>You rarely name these directly — the {@link Question} factories pick the right one — but they
 * are handy for logging or branching on an answer you did not build yourself:
 *
 * <pre>{@code
 * if (response.answer("verdict").type() == QuestionType.NOUL) {
 *     log.info("probability: {}", response.noul("verdict").noul());
 * }
 * }</pre>
 */
public enum QuestionType {
    /** Yes/no judgement, answered with a probability. */
    NOUL("noul"),
    /** Pick one of the choices you define. */
    CHOICE("choice"),
    /** Rate the content against an ordered rubric. */
    SCORE("score");

    private final String wireName;

    QuestionType(String wireName) {
        this.wireName = wireName;
    }

    /**
     * The value used on the wire, e.g. {@code "noul"}.
     *
     * @return the lowercase name the API uses in its {@code type} discriminator
     */
    @JsonValue
    public String wireName() {
        return wireName;
    }
}
