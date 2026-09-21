package com.luigivismara.jev;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Model aliases published by TypeSafe AI. Aliases are stable; the concrete version behind them
 * is not, so {@link JevResponse#model()} may report something like {@code jev-1.13.0}.
 *
 * <p>If TypeSafe publishes a name that is not listed here yet, pass it as a raw string via
 * {@link JevRequest.Builder#model(String)}. Use {@link JevClient#models()} to discover them.
 *
 * <pre>{@code
 * jev.ask(ticket, JevModel.JEV_PREVIEW,
 *         Question.noul("Does this need attention today?").named("urgent"));
 *
 * // A model published after this SDK shipped:
 * JevRequest.builder().state(ticket).model("jev-2.0.0-experimental")
 *         .ask("urgent", Question.noul("Does this need attention today?"))
 *         .build();
 * }</pre>
 */
public enum JevModel {
    /** The latest iteration of TypeSafe's System One model. */
    JEV_LATEST("jev-latest"),
    /** Preview version of {@code jev-latest}. */
    JEV_PREVIEW("jev-preview");

    private final String id;

    JevModel(String id) {
        this.id = id;
    }

    /**
     * The value used on the wire, e.g. {@code "jev-latest"}.
     *
     * <pre>{@code
     * boolean available = jev.models().stream()
     *         .anyMatch(model -> model.name().equals(JevModel.JEV_LATEST.id()));
     * }</pre>
     *
     * @return the alias the API accepts in a request's {@code model} field
     */
    @JsonValue
    public String id() {
        return id;
    }
}
