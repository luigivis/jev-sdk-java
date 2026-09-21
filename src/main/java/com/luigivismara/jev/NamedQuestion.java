package com.luigivismara.jev;

/**
 * A question paired with the name you want its answer filed under.
 *
 * <p>Build one with {@link Question#named(String)}:
 * <pre>{@code Question.noul("Is this urgent?").named("urgent")}</pre>
 *
 * @param name     the name the answer comes back under; must not be blank
 * @param question the question itself
 */
public record NamedQuestion(String name, Question question) {

    /**
     * Validates the pair.
     *
     * @throws IllegalArgumentException if {@code name} is blank or {@code question} is null
     */
    public NamedQuestion {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("question name is required");
        }
        if (question == null) {
            throw new IllegalArgumentException("question is required for '" + name + "'");
        }
    }
}
