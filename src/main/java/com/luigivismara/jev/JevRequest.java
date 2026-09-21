package com.luigivismara.jev;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Content plus the named questions to evaluate against it.
 *
 * @param state     the content every question refers to: a string, a map or a list
 * @param model     model name or alias, see {@link JevModel} and {@link JevClient#models()}
 * @param questions the questions, in the order you asked them; names must be distinct
 */
public record JevRequest(Object state, String model, @JsonIgnore List<NamedQuestion> questions) {

    /**
     * Validates and defensively copies the questions.
     *
     * @throws IllegalArgumentException if state, model or questions are missing, or if two
     *                                  questions share a name
     */
    public JevRequest {
        if (state == null) {
            throw new IllegalArgumentException("state is required");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("at least one question is required");
        }
        questions = List.copyOf(questions);
        if (questions.stream().map(NamedQuestion::name).distinct().count() != questions.size()) {
            throw new IllegalArgumentException("question names must be distinct: "
                    + questions.stream().map(NamedQuestion::name).toList());
        }
    }

    /**
     * A request using {@link JevModel} instead of a raw model name.
     *
     * @param state     the content every question refers to
     * @param model     the model or alias to use
     * @param questions the questions, with distinct names
     */
    public JevRequest(Object state, JevModel model, List<NamedQuestion> questions) {
        this(state, model == null ? null : model.id(), questions);
    }

    /**
     * A request from a varargs list of questions.
     *
     * <pre>{@code
     * JevRequest request = new JevRequest(ticket, JevModel.JEV_LATEST,
     *         Question.noul("Is this urgent?").named("urgent"),
     *         Question.choice("Which team?", TEAMS).named("routing"));
     * }</pre>
     *
     * @param state     the content every question refers to
     * @param model     the model or alias to use
     * @param questions the questions, with distinct names
     */
    public JevRequest(Object state, JevModel model, NamedQuestion... questions) {
        this(state, model, questions == null ? List.of() : List.of(questions));
    }

    /**
     * Starts a builder defaulting to {@link JevModel#JEV_LATEST}.
     *
     * <pre>{@code
     * JevRequest request = JevRequest.builder()
     *         .state(ticket)
     *         .ask("routing",  Question.choice("Which team?", TEAMS))
     *         .ask("urgent",   Question.noul("Does this need attention today?"))
     *         .ask("severity", Question.score("How severe?", "Low", "Medium", "High"))
     *         .build();
     * }</pre>
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The question filed under {@code name}.
     *
     * <p>Lets you pair an answer back up with what you asked — useful for logging why a decision
     * came out the way it did:
     *
     * <pre>{@code
     * Question.Choice asked = (Question.Choice) request.question("routing");
     * Object why = asked.option(response.choice("routing").choice()).description();
     * }</pre>
     *
     * @param name the question name
     * @return the question
     * @throws IllegalArgumentException if this request has no such question
     */
    public Question question(String name) {
        return questions.stream()
                .filter(question -> question.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no question named '" + name + "'"))
                .question();
    }

    /** Wire form: the API expects questions as a name-to-question JSON object. */
    @JsonProperty("questions")
    Map<String, Question> questionsJson() {
        var byName = new LinkedHashMap<String, Question>();
        questions.forEach(question -> byName.put(question.name(), question.question()));
        return byName;
    }

    /** Accumulates questions before building an immutable {@link JevRequest}. */
    public static final class Builder {
        private Object state;
        private String model = JevModel.JEV_LATEST.id();
        private final List<NamedQuestion> questions = new ArrayList<>();

        private Builder() {
        }

        /**
         * The content the questions refer to. Required.
         *
         * <p>A plain string works, and so does any record or POJO you already have — passing your
         * own domain type is usually better, because the field names become part of the context
         * the model reads:
         *
         * <pre>{@code
         * record Ticket(String subject, String message, String customerTier) {}
         *
         * .state(new Ticket("Package never arrived", "It has been three weeks.", "gold"))
         * // {"state": {"subject": "...", "message": "...", "customerTier": "gold"}}
         * }</pre>
         *
         * @param state the content; must not be null at build time
         * @return this builder
         */
        public Builder state(Object state) {
            this.state = state;
            return this;
        }

        /**
         * The model to use. Defaults to {@link JevModel#JEV_LATEST}.
         *
         * @param model the model or alias
         * @return this builder
         */
        public Builder model(JevModel model) {
            this.model = model == null ? null : model.id();
            return this;
        }

        /**
         * Escape hatch for a model name TypeSafe published after this release.
         *
         * <p>{@link JevModel} only lists the aliases known when this SDK shipped. Discover the
         * rest with {@link JevClient#models()} and pass the name straight through:
         *
         * <pre>{@code
         * .model("jev-2.0.0-experimental")
         * }</pre>
         *
         * @param model the raw model name
         * @return this builder
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * Adds a question under {@code name}. Reusing a name replaces the previous question,
         * keeping its position — handy when a caller layers a default set with an override:
         *
         * <pre>{@code
         * JevRequest.Builder builder = JevRequest.builder().state(ticket);
         * defaults.forEach((name, question) -> builder.ask(name, question));
         * builder.ask("urgent", Question.noul("Is this a VIP outage?"));   // overrides the default
         * }</pre>
         *
         * @param name     the name to file the answer under
         * @param question the question
         * @return this builder
         * @throws IllegalArgumentException if {@code name} is blank or {@code question} is null
         */
        public Builder ask(String name, Question question) {
            return ask(new NamedQuestion(name, question));
        }

        /**
         * Adds already-named questions.
         *
         * <pre>{@code
         * builder.ask(
         *         Question.noul("Is this urgent?").named("urgent"),
         *         Question.choice("Which team?", TEAMS).named("routing"));
         * }</pre>
         *
         * @param named the questions to add
         * @return this builder
         */
        public Builder ask(NamedQuestion... named) {
            for (NamedQuestion question : named) {
                questions.removeIf(existing -> existing.name().equals(question.name()));
                questions.add(question);
            }
            return this;
        }

        /**
         * Builds the request.
         *
         * @return the immutable request
         * @throws IllegalArgumentException if state, model or questions are missing, or if two
         *                                  questions share a name
         */
        public JevRequest build() {
            return new JevRequest(state, model, questions);
        }
    }
}
