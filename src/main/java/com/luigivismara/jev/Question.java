package com.luigivismara.jev;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A question about the content in {@link JevRequest#state()}.
 *
 * <p>Sealed, so {@code switch} over a question is exhaustive without a default branch.
 *
 * <p>{@code instructions} and criteria descriptions are typed as {@link Object} because the API
 * accepts a string, a map or a list there — pass whatever shape reads best for your domain.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Question.Noul.class, name = "noul"),
        @JsonSubTypes.Type(value = Question.Choice.class, name = "choice"),
        @JsonSubTypes.Type(value = Question.Score.class, name = "score")
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface Question permits Question.Noul, Question.Choice, Question.Score {

    /**
     * Which primitive this question uses.
     *
     * @return the question's type
     */
    @JsonIgnore
    QuestionType type();

    /**
     * Pairs this question with the name its answer should come back under.
     *
     * <pre>{@code
     * JevResponse response = jev.ask(ticket,
     *         Question.noul("Is this urgent?").named("urgent"));
     *
     * response.noul("urgent").noul();   // the answer, under the same name
     * }</pre>
     *
     * @param name the name to file the answer under; must not be blank
     * @return this question, paired with {@code name}
     * @throws IllegalArgumentException if {@code name} is null or blank
     */
    default NamedQuestion named(String name) {
        return new NamedQuestion(name, this);
    }

    /**
     * A yes/no question or statement, answered with the probability of yes.
     *
     * <p>Phrase it either way — both forms work, and a statement often reads better in a rule:
     *
     * <pre>{@code
     * Question.noul("Is this message spam?");
     * Question.noul("This message contains unsolicited advertising.");
     * }</pre>
     *
     * <p>Read the answer as a probability, not a verdict. A value near {@code 0.5} means the model
     * is genuinely undecided, which is worth routing differently from a confident yes or no:
     *
     * <pre>{@code
     * double spam = response.noul("spam").noul();
     * if (spam > 0.9)      block();
     * else if (spam > 0.4) quarantineForReview();
     * else                 deliver();
     * }</pre>
     *
     * @param instructions the question or statement to evaluate; a string, or any value that
     *                     serializes to JSON. May be {@code null} when the criteria say enough.
     * @return the question
     */
    static Noul noul(Object instructions) {
        return new Noul(instructions, null);
    }

    /**
     * A yes/no question with explicit criteria for what counts as yes and as no.
     *
     * <p>Spell the two sides out when the question alone is ambiguous — "is this spam?" means
     * different things to a marketing team and an abuse team:
     *
     * <pre>{@code
     * Question.noul("Is this message spam?",
     *         "Unsolicited advertising sent without any prior relationship",
     *         "A legitimate conversation, including a reply to our own outreach");
     * }</pre>
     *
     * @param instructions the question or statement to evaluate, may be {@code null}
     * @param whenTrue     what counts as a yes
     * @param whenFalse    what counts as a no
     * @return the question
     */
    static Noul noul(Object instructions, Object whenTrue, Object whenFalse) {
        return new Noul(instructions, new Noul.Criteria(whenTrue, whenFalse));
    }

    /**
     * A selectable option with a description of when it applies.
     *
     * <p>The description is what the model reads to decide; the name is what comes back in
     * {@link Answer.Choice#choice()}, so keep it a stable identifier you can switch on:
     *
     * <pre>{@code
     * Question.option("logistics", "Shipping, delivery delays and lost packages");
     * }</pre>
     *
     * @param name        the option's identifier; must not be blank
     * @param description when this option applies, in whatever detail helps
     * @return the option
     * @throws IllegalArgumentException if {@code name} is null or blank
     */
    static Option option(String name, Object description) {
        return new Option(name, description);
    }

    /**
     * A selectable option interpreted by its name alone.
     *
     * <p>Fine when the names speak for themselves, as in a sentiment split:
     *
     * <pre>{@code
     * Question.choice("What is the tone of this message?",
     *         Question.option("angry"),
     *         Question.option("calm"),
     *         Question.option("excited"));
     * }</pre>
     *
     * <p>A one-line description measurably helps on anything less obvious — prefer
     * {@link #option(String, Object)} when the names are domain jargon.
     *
     * @param name the option's identifier; must not be blank
     * @return the option, with no description
     * @throws IllegalArgumentException if {@code name} is null or blank
     */
    static Option option(String name) {
        return new Option(name, null);
    }

    /**
     * A question that selects one of {@code options}.
     *
     * <pre>{@code
     * Question.choice("Which team should handle this ticket?",
     *         Question.option("support",   "General product help"),
     *         Question.option("logistics", "Shipping, delivery and lost packages"),
     *         Question.option("billing",   "Charges, refunds and invoices"));
     * }</pre>
     *
     * <p>Options are typically constant, so hoist them out of the hot path:
     *
     * <pre>{@code
     * private static final Question.Option[] TEAMS = {
     *         Question.option("support",   "General product help"),
     *         Question.option("logistics", "Shipping, delivery and lost packages"),
     *         Question.option("billing",   "Charges, refunds and invoices")
     * };
     *
     * jev.ask(ticket, Question.choice("Which team?", TEAMS).named("routing"));
     * }</pre>
     *
     * @param instructions what the model should decide, may be {@code null}
     * @param options      the options to choose between; at least one, with distinct names
     * @return the question
     * @throws IllegalArgumentException if {@code options} is empty or has duplicate names
     */
    static Choice choice(Object instructions, Option... options) {
        return new Choice(instructions, options == null ? List.of() : List.of(options));
    }

    /**
     * A question that rates the content against an ordered rubric. The first level scores 0.
     *
     * <pre>{@code
     * Question.score("How urgent is this message?",
     *         "Can wait",                   // scores 0
     *         "Needs attention this week",  // scores 1
     *         "Needs attention today");     // scores 2
     * }</pre>
     *
     * <p>Order is the whole contract: the position of each description <em>is</em> its score, so
     * write the rubric from lowest to highest and keep it stable across releases — reordering it
     * silently changes the meaning of every score you have already stored.
     *
     * <p>The answer is an expected value, so it usually lands between levels. Use
     * {@link Answer.Score#score()} when you want the gradient and
     * {@link Answer.Score#nearestLevel()} when you need a discrete bucket:
     *
     * <pre>{@code
     * Answer.Score urgency = response.score("urgency");
     * urgency.score();          // 1.66 — closer to "today" than to "this week"
     * urgency.nearestLevel();   // 2
     * }</pre>
     *
     * @param instructions what the model should rate, may be {@code null}
     * @param levels       ordered level descriptions, at least one; the first scores 0
     * @return the question
     * @throws IllegalArgumentException if {@code levels} is empty
     */
    static Score score(Object instructions, Object... levels) {
        return new Score(instructions, levels == null ? List.of() : List.of(levels));
    }

    /**
     * One option of a {@link Choice} question.
     *
     * @param name        the option's name, which is what a {@link Answer.Choice} reports
     * @param description when the option applies; {@code null} means "read the name literally"
     */
    record Option(String name, Object description) {

        /**
         * Validates the option.
         *
         * @throws IllegalArgumentException if {@code name} is null or blank
         */
        public Option {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("option name is required");
            }
        }
    }

    /**
     * A yes/no question or statement.
     *
     * @param instructions the question or statement to evaluate, may be {@code null}
     * @param criteria     what counts as yes and as no, may be {@code null}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Noul(Object instructions, Criteria criteria) implements Question {

        @Override
        public QuestionType type() {
            return QuestionType.NOUL;
        }

        /**
         * Criteria defining the two sides of a {@link Noul} question. Named {@code yes}/{@code no}
         * here because {@code true} and {@code false} are Java keywords; they are serialized as
         * {@code "true"} and {@code "false"}.
         *
         * @param yes what counts as a yes answer, may be {@code null}
         * @param no  what counts as a no answer, may be {@code null}
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Criteria(
                @JsonProperty("true") Object yes,
                @JsonProperty("false") Object no) {
        }
    }

    /**
     * A question that selects one option.
     *
     * @param instructions what the model should decide, may be {@code null}
     * @param options      the options to choose between, at least one, names must be distinct
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Choice(Object instructions, @JsonIgnore List<Option> options) implements Question {

        /**
         * Validates and defensively copies the options.
         *
         * @throws IllegalArgumentException if {@code options} is empty or names are not distinct
         */
        public Choice {
            if (options == null || options.isEmpty()) {
                throw new IllegalArgumentException("choice question needs at least one option");
            }
            options = List.copyOf(options);
            if (options.stream().map(Option::name).distinct().count() != options.size()) {
                throw new IllegalArgumentException("choice option names must be distinct: "
                        + options.stream().map(Option::name).toList());
            }
        }

        @Override
        public QuestionType type() {
            return QuestionType.CHOICE;
        }

        /**
         * The option with the given name, or {@code null} if this question has no such option.
         *
         * <p>Useful for turning an answer back into the option you defined:
         *
         * <pre>{@code
         * Question.Choice question = (Question.Choice) request.question("routing");
         * Object why = question.option(response.choice("routing").choice()).description();
         * }</pre>
         *
         * @param name the option name to look for
         * @return the matching option, or {@code null}
         */
        public Option option(String name) {
            return options.stream().filter(o -> o.name().equals(name)).findFirst().orElse(null);
        }

        /** Wire form: the API expects criteria as a name-to-description JSON object. */
        @JsonProperty("criteria")
        Map<String, Object> criteriaJson() {
            var criteria = new LinkedHashMap<String, Object>();
            options.forEach(option -> criteria.put(option.name(),
                    option.description() == null ? "" : option.description()));
            return criteria;
        }
    }

    /**
     * A question that assigns a score using an ordered rubric.
     *
     * @param instructions what the model should rate, may be {@code null}
     * @param criteria     ordered level descriptions, at least one; the first scores 0
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Score(Object instructions, List<Object> criteria) implements Question {

        /**
         * Validates and defensively copies the rubric.
         *
         * @throws IllegalArgumentException if {@code criteria} is empty
         */
        public Score {
            if (criteria == null || criteria.isEmpty()) {
                throw new IllegalArgumentException("score question needs at least one level");
            }
            criteria = List.copyOf(criteria);
        }

        @Override
        public QuestionType type() {
            return QuestionType.SCORE;
        }
    }
}
