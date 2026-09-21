package com.luigivismara.jev;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * An answer to a {@link Question}. The variant always matches the question's type.
 *
 * <p>Sealed, so you can switch exhaustively:
 * <pre>{@code
 * String label = switch (answer) {
 *     case Answer.Noul n   -> n.noul() > 0.8 ? "yes" : "no";
 *     case Answer.Choice c -> c.choice();
 *     case Answer.Score s  -> "level " + s.nearestLevel();
 * };
 * }</pre>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Answer.Noul.class, name = "noul"),
        @JsonSubTypes.Type(value = Answer.Choice.class, name = "choice"),
        @JsonSubTypes.Type(value = Answer.Score.class, name = "score")
})
public sealed interface Answer permits Answer.Noul, Answer.Choice, Answer.Score {

    /**
     * Which primitive produced this answer.
     *
     * @return the answer's type, always matching the question's
     */
    @JsonIgnore
    QuestionType type();

    /**
     * Pairs this answer with a question name. Mostly useful in tests, where you build a response
     * by hand:
     *
     * <pre>{@code
     * JevResponse canned = new JevResponse("jev-latest",
     *         List.of(new Answer.Noul(0.92).named("urgent")),
     *         new JevResponse.Usage(120, 12));
     * }</pre>
     *
     * @param name the question name to file this answer under
     * @return this answer, paired with {@code name}
     */
    default NamedAnswer named(String name) {
        return new NamedAnswer(name, this);
    }

    /**
     * The probability of a yes answer or a true statement.
     *
     * <p>Treat it as a probability, not a boolean. Picking a threshold is a product decision, and
     * the band in the middle is where a human belongs:
     *
     * <pre>{@code
     * double spam = response.noul("spam").noul();
     * if (spam > 0.9)      block();
     * else if (spam > 0.4) quarantineForReview();
     * else                 deliver();
     * }</pre>
     *
     * @param noul value in {@code [0, 1]}; near 0.5 means the model is undecided
     */
    record Noul(@JsonProperty(value = "noul", required = true) double noul) implements Answer {

        @Override
        public QuestionType type() {
            return QuestionType.NOUL;
        }
    }

    /**
     * The selected option, with the full distribution over the alternatives.
     *
     * <p>{@link #outcomes()} is sorted most-likely first, so {@code outcomes().get(0)} is always
     * the selected option and {@link #runnerUp()} is what it beat. Looking at the runner-up is how
     * you tell a decisive answer from a coin flip that happened to land:
     *
     * <pre>{@code
     * Answer.Choice routing = response.choice("routing");
     *
     * if (routing.confidence() < 0.7) {
     *     // 0.51 / 0.49 and 0.95 / 0.05 both report the same choice(); only the
     *     // distribution tells you which one you are actually looking at.
     *     queueForHumanReview(ticket, routing.outcomes());
     * } else {
     *     assignTo(routing.choice());
     * }
     * }</pre>
     *
     * @param choice     name of the highest-probability option
     * @param confidence confidence in the selection, in {@code [0, 1]}
     * @param outcomes   every option with its probability, highest first, summing to roughly 1
     */
    record Choice(String choice, double confidence, @JsonIgnore List<Outcome> outcomes)
            implements Answer {

        /**
         * Validates and defensively copies the outcomes.
         *
         * @throws JevException if the choice or the outcomes are missing
         */
        public Choice {
            if (choice == null || outcomes == null || outcomes.isEmpty()) {
                throw new JevException("choice answer is missing its choice or probabilities");
            }
            outcomes = List.copyOf(outcomes);
        }

        @Override
        public QuestionType type() {
            return QuestionType.CHOICE;
        }

        /**
         * The probability assigned to {@code option}.
         *
         * <p>Use it when you care about one specific option regardless of what won:
         *
         * <pre>{@code
         * // Escalate anything with a whiff of fraud, even if it routed elsewhere.
         * if (response.choice("routing").probabilityOf("fraud") > 0.2) {
         *     notifyRiskTeam();
         * }
         * }</pre>
         *
         * @param option the option name to look up
         * @return its probability, in {@code [0, 1]}
         * @throws JevException if the answer has no such option
         */
        public double probabilityOf(String option) {
            return outcomes.stream()
                    .filter(outcome -> outcome.choice().equals(option))
                    .findFirst()
                    .orElseThrow(() -> new JevException("no option named '" + option
                            + "' in this answer; got " + outcomes.stream()
                            .map(Outcome::choice).toList()))
                    .probability();
        }

        /**
         * The runner-up, or {@code null} when the question had a single option.
         *
         * <p>A small margin is the clearest signal that the model was torn:
         *
         * <pre>{@code
         * Answer.Choice routing = response.choice("routing");
         * Answer.Choice.Outcome second = routing.runnerUp();
         *
         * if (second != null
         *         && routing.outcomes().get(0).probability() - second.probability() < 0.15) {
         *     log.warn("close call: {} vs {}", routing.choice(), second.choice());
         * }
         * }</pre>
         *
         * @return the second most likely outcome, or {@code null}
         */
        public Outcome runnerUp() {
            return outcomes.size() < 2 ? null : outcomes.get(1);
        }

        /** Wire form: the API sends probabilities as a name-to-number JSON object. */
        @JsonCreator
        static Choice fromJson(
                @JsonProperty(value = "choice", required = true) String choice,
                @JsonProperty(value = "confidence", required = true) double confidence,
                @JsonProperty(value = "probabilities", required = true)
                Map<String, Double> probabilities) {
            if (probabilities == null) {
                throw new JevException("choice answer is missing its probabilities");
            }
            List<Outcome> outcomes = new ArrayList<>();
            probabilities.forEach((name, probability) ->
                    outcomes.add(new Outcome(name, probability)));
            outcomes.sort(Comparator.comparingDouble(Outcome::probability).reversed());
            return new Choice(choice, confidence, outcomes);
        }

        /**
         * One option and how likely the model thought it was.
         *
         * <pre>{@code
         * for (Answer.Choice.Outcome outcome : response.choice("routing").outcomes()) {
         *     log.info("{}: {}", outcome.choice(), outcome.probability());
         * }
         * // billing: 0.69
         * // support: 0.28
         * // logistics: 0.03
         * }</pre>
         *
         * @param choice      the option's name
         * @param probability its probability, in {@code [0, 1]}
         */
        public record Outcome(String choice, double probability) {
        }
    }

    /**
     * A rating against the requested rubric.
     *
     * <p>The API sends the rubric as two parallel JSON objects — a legend and a set of
     * probabilities, both keyed by level index. This record merges them into one ordered list, so
     * a level's text and its likelihood stay together:
     *
     * <pre>{@code
     * Answer.Score severity = response.score("severity");
     *
     * severity.score();               // 1.66  — the expected value, between levels
     * severity.nearestLevel();        // 2
     * severity.nearestDescription();  // "Needs attention today"
     *
     * for (Answer.Score.Level level : severity.levels()) {
     *     log.info("{} ({}): {}", level.position(), level.probability(), level.description());
     * }
     * }</pre>
     *
     * @param score      probability-weighted average of the levels; may fall between integers
     * @param confidence confidence in the score, in {@code [0, 1]}
     * @param levels     the rubric in order, each with its description and probability
     */
    record Score(double score, double confidence, @JsonIgnore List<Level> levels)
            implements Answer {

        /**
         * Validates and defensively copies the rubric.
         *
         * @throws JevException if the rubric is missing
         */
        public Score {
            if (levels == null || levels.isEmpty()) {
                throw new JevException("score answer is missing its rubric");
            }
            levels = List.copyOf(levels);
        }

        @Override
        public QuestionType type() {
            return QuestionType.SCORE;
        }

        /**
         * The nearest whole rubric level, for when you need a discrete bucket.
         *
         * <pre>{@code
         * switch (response.score("severity").nearestLevel()) {
         *     case 0  -> backlog(ticket);
         *     case 1  -> thisWeek(ticket);
         *     default -> pageOnCall(ticket);
         * }
         * }</pre>
         *
         * <p>Prefer {@link #score()} when you are sorting or thresholding — rounding throws away
         * the difference between a 1.51 and a 2.49.
         *
         * @return {@link #score()} rounded to the nearest level
         */
        public int nearestLevel() {
            return (int) Math.round(score);
        }

        /**
         * The rubric entry at {@code position}.
         *
         * <pre>{@code
         * Answer.Score.Level worst = response.score("severity").levelAt(2);
         * worst.description();   // "Needs attention today"
         * worst.probability();   // 0.66
         * }</pre>
         *
         * @param position the level index, starting at 0
         * @return the rubric entry
         * @throws JevException if the rubric has no such level
         */
        public Level levelAt(int position) {
            return levels.stream()
                    .filter(level -> level.position() == position)
                    .findFirst()
                    .orElseThrow(() -> new JevException("no rubric level " + position
                            + "; the rubric has " + levels.size() + " level(s)"));
        }

        /**
         * The description of {@link #nearestLevel()}, ready to show or log.
         *
         * <p>Saves you from keeping the rubric around just to translate a number back into words:
         *
         * <pre>{@code
         * log.info("severity: {}", response.score("severity").nearestDescription());
         * // severity: Needs attention today
         * }</pre>
         *
         * @return the criterion text of the nearest level
         * @throws JevException if the rubric has no level at {@link #nearestLevel()}
         */
        public Object nearestDescription() {
            return levelAt(nearestLevel()).description();
        }

        /** Wire form: the API sends the rubric as two JSON objects keyed by level index. */
        @JsonCreator
        static Score fromJson(
                @JsonProperty(value = "score", required = true) double score,
                @JsonProperty(value = "confidence", required = true) double confidence,
                @JsonProperty(value = "legend", required = true) Map<String, Object> legend,
                @JsonProperty(value = "probabilities", required = true)
                Map<String, Double> probabilities) {
            if (legend == null || probabilities == null) {
                throw new JevException("score answer is missing its legend or probabilities");
            }
            List<Level> levels = new ArrayList<>();
            legend.forEach((position, description) ->
                    levels.add(new Level(parsePosition(position), description,
                            probabilities.getOrDefault(position, 0.0))));
            levels.sort(Comparator.comparingInt(Level::position));
            return new Score(score, confidence, levels);
        }

        private static int parsePosition(String position) {
            try {
                return Integer.parseInt(position);
            } catch (NumberFormatException e) {
                // Fail closed: a non-numeric rubric key is not something to guess at.
                throw new JevException("rubric level '" + position + "' is not a number", e);
            }
        }

        /**
         * One level of the rubric: the criterion you sent, the score it carries, and how likely
         * the model thought it was.
         *
         * <pre>{@code
         * for (Answer.Score.Level level : response.score("severity").levels()) {
         *     log.info("{} ({}): {}", level.position(), level.probability(), level.description());
         * }
         * // 0 (0.01): Can wait
         * // 1 (0.33): Needs attention this week
         * // 2 (0.66): Needs attention today
         * }</pre>
         *
         * @param position    the level's score, starting at 0
         * @param description the criterion text sent in the request
         * @param probability how likely this level is, in {@code [0, 1]}
         */
        public record Level(int position, Object description, double probability) {
        }
    }
}
