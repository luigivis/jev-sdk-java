package com.luigivismara.jev;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Answers filed under the question names from the request.
 *
 * @param model   the model that actually answered; may be a concrete version behind an alias
 * @param answers the answers, one per question asked
 * @param usage   token usage for this call
 */
public record JevResponse(String model, @JsonIgnore List<NamedAnswer> answers, Usage usage) {

    /**
     * Validates and defensively copies the answers.
     *
     * @throws JevException if model, answers or usage are missing
     */
    public JevResponse {
        if (model == null || answers == null || answers.isEmpty() || usage == null) {
            // Fail closed: a body missing these is not a decision, whatever its HTTP status was.
            throw new JevException("response is missing model, answers or usage");
        }
        answers = List.copyOf(answers);
    }

    /**
     * The answer for {@code name}, whatever its type.
     *
     * <p>Reach for this when you are handling answers generically. Because {@link Answer} is
     * sealed, the switch is exhaustive and the compiler will flag it if a new primitive ever
     * appears:
     *
     * <pre>{@code
     * String rendered = switch (response.answer("verdict")) {
     *     case Answer.Noul n   -> n.noul() > 0.5 ? "yes" : "no";
     *     case Answer.Choice c -> c.choice();
     *     case Answer.Score s  -> String.valueOf(s.nearestDescription());
     * };
     * }</pre>
     *
     * @param name the question name from the request
     * @return the answer
     * @throws JevException if the response has no answer under that name
     */
    public Answer answer(String name) {
        return answers.stream()
                .filter(answer -> answer.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new JevException("no answer named '" + name + "' in response; "
                        + "got " + names()))
                .answer();
    }

    /**
     * The question names this response carries answers for, in order.
     *
     * @return the names, in the order the API returned them
     */
    public List<String> names() {
        return answers.stream().map(NamedAnswer::name).toList();
    }

    /**
     * The answer for {@code name}, as a yes/no probability.
     *
     * <pre>{@code
     * if (response.noul("urgent").noul() > 0.8) {
     *     pageOnCall();
     * }
     * }</pre>
     *
     * <p>Fails closed: if {@code name} was asked as a choice or a score, this throws rather than
     * handing you a plausible-looking default.
     *
     * @param name the question name from the request
     * @return the yes/no answer
     * @throws JevException if the answer is missing or is not a noul answer
     */
    public Answer.Noul noul(String name) {
        return as(name, Answer.Noul.class);
    }

    /**
     * The answer for {@code name}, as a selected option.
     *
     * <pre>{@code
     * Answer.Choice routing = response.choice("routing");
     * assignTo(routing.confidence() < 0.7 ? "manual-review" : routing.choice());
     * }</pre>
     *
     * @param name the question name from the request
     * @return the choice answer
     * @throws JevException if the answer is missing or is not a choice answer
     */
    public Answer.Choice choice(String name) {
        return as(name, Answer.Choice.class);
    }

    /**
     * The answer for {@code name}, as a rating.
     *
     * <pre>{@code
     * Answer.Score severity = response.score("severity");
     * log.info("severity {} ({})", severity.score(), severity.nearestDescription());
     * }</pre>
     *
     * @param name the question name from the request
     * @return the score answer
     * @throws JevException if the answer is missing or is not a score answer
     */
    public Answer.Score score(String name) {
        return as(name, Answer.Score.class);
    }

    private <T extends Answer> T as(String name, Class<T> expected) {
        Answer answer = answer(name);
        if (!expected.isInstance(answer)) {
            // Fail closed: never coerce a mismatched answer into a default branch.
            throw new JevException("answer '" + name + "' is " + answer.type().wireName()
                    + ", not " + expected.getSimpleName().toLowerCase());
        }
        return expected.cast(answer);
    }

    /** Wire form: the API sends answers as a name-to-answer JSON object. */
    @JsonCreator
    static JevResponse fromJson(
            @JsonProperty(value = "model", required = true) String model,
            @JsonProperty(value = "answers", required = true) Map<String, Answer> answers,
            @JsonProperty(value = "usage", required = true) Usage usage) {
        if (answers == null) {
            throw new JevException("response is missing its answers");
        }
        List<NamedAnswer> named = new ArrayList<>();
        answers.forEach((name, answer) -> named.add(new NamedAnswer(name, answer)));
        return new JevResponse(model, named, usage);
    }

    /**
     * Token usage. Output tokens are free on TypeSafe's published rate card; input tokens are not,
     * so this is the number to meter:
     *
     * <pre>{@code
     * meter.counter("jev.input_tokens").increment(response.usage().inputTokens());
     * }</pre>
     *
     * @param inputTokens  billable input tokens used to evaluate the request
     * @param outputTokens output tokens used to answer; currently free of charge
     */
    public record Usage(
            @JsonProperty(value = "input_tokens", required = true) int inputTokens,
            @JsonProperty(value = "output_tokens", required = true) int outputTokens) {
    }
}
