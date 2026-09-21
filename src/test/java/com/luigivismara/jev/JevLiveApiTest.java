package com.luigivismara.jev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests against the real api.typesafe.ai. Skipped unless {@code JEV_API_KEY} is set,
 * so an ordinary {@code mvn test} costs nothing and needs no network.
 *
 * <pre>{@code export JEV_API_KEY=... && mvn test}</pre>
 */
@EnabledIfEnvironmentVariable(named = JevClient.API_KEY_ENV, matches = ".+")
class JevLiveApiTest {

    private static final Question.Option[] TEAMS = {
            Question.option("support", "General product help"),
            Question.option("logistics", "Shipping, delivery and lost packages"),
            Question.option("billing", "Charges, refunds and invoices")
    };

    /** The content under evaluation; a record serializes straight into the request's state. */
    private record Ticket(String subject, String message) {
    }

    @Test
    @DisplayName("live: the account can list models and jev-latest is among them")
    void listsModels() {
        try (JevClient jev = JevClient.fromEnv()) {
            List<ModelInfo> models = jev.models();

            assertFalse(models.isEmpty());
            assertTrue(models.stream().anyMatch(m -> m.name().equals(JevModel.JEV_LATEST.id())),
                    () -> "jev-latest missing from " + models);
        }
    }

    @Test
    @DisplayName("live: one request answers a choice, a noul and a score together")
    void answersAllThreePrimitives() {
        try (JevClient jev = JevClient.fromEnv()) {
            JevResponse response = jev.ask(
                    new Ticket("Package never arrived",
                            "My order has not arrived and I was charged twice."),
                    Question.choice("Which team should handle this?", TEAMS).named("routing"),
                    Question.noul("Does this need attention today?").named("urgent"),
                    Question.score("How severe is this for the customer?",
                            "Can wait", "Needs attention this week", "Needs attention today")
                            .named("severity"));

            assertNotNull(response.model());
            assertEquals(List.of("routing", "urgent", "severity").size(),
                    response.answers().size());

            Answer.Choice routing = response.choice("routing");
            assertTrue(List.of(TEAMS).stream()
                            .anyMatch(team -> team.name().equals(routing.choice())),
                    routing.choice());
            assertTrue(routing.confidence() >= 0 && routing.confidence() <= 1);
            assertEquals(1.0, routing.outcomes().stream()
                    .mapToDouble(Answer.Choice.Outcome::probability).sum(), 0.05);
            assertEquals(routing.choice(), routing.outcomes().get(0).choice(),
                    "outcomes must be ordered with the selected choice first");

            double urgent = response.noul("urgent").noul();
            assertTrue(urgent >= 0 && urgent <= 1, "noul out of range: " + urgent);

            Answer.Score severity = response.score("severity");
            assertTrue(severity.score() >= 0 && severity.score() <= 2, "score: " + severity.score());
            assertEquals(3, severity.levels().size());
            assertEquals("Can wait", severity.levelAt(0).description());
            assertNotNull(severity.nearestDescription());

            assertTrue(response.usage().inputTokens() > 0);
        }
    }

    @Test
    @DisplayName("live: a clearly non-urgent message scores lower than an emergency")
    void discriminatesUrgency() {
        Question urgency = Question.noul("Does this require immediate action?");

        try (JevClient jev = JevClient.fromEnv()) {
            double calm = jev.ask("Just saying thanks, the delivery was great.",
                    urgency.named("urgent")).noul("urgent").noul();
            double alarm = jev.ask("My account was charged five times and I need this fixed now.",
                    urgency.named("urgent")).noul("urgent").noul();

            assertTrue(alarm > calm, "expected " + alarm + " > " + calm);
        }
    }

    @Test
    @DisplayName("live: a bad key is rejected with a 4xx, not a retry storm")
    void rejectsBadKey() {
        try (JevClient jev = JevClient.builder().apiKey("apikey_not_a_real_key").build()) {
            JevException e = assertThrows(JevException.class,
                    () -> jev.ask("hello", Question.noul("Is this a greeting?").named("q")));

            assertTrue(e.statusCode() >= 400 && e.statusCode() < 500,
                    "unexpected status " + e.statusCode() + ": " + e.getMessage());
        }
    }
}
