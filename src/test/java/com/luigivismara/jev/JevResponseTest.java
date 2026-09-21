package com.luigivismara.jev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the typed accessors, including the fail-closed behaviour on a type mismatch. */
class JevResponseTest {

    private final JevResponse response = new JevResponse(
            "jev-1.13.0",
            List.of(
                    new Answer.Noul(0.84).named("urgent"),
                    new Answer.Choice("billing", 0.54,
                            List.of(new Answer.Choice.Outcome("billing", 1.0))).named("routing"),
                    new Answer.Score(1.66, 0.49,
                            List.of(new Answer.Score.Level(0, "Low", 1.0))).named("severity")),
            new JevResponse.Usage(380, 73));

    @Test
    @DisplayName("typed accessors return the matching variant")
    void typedAccessors() {
        assertEquals(0.84, response.noul("urgent").noul(), 1e-9);
        assertEquals("billing", response.choice("routing").choice());
        assertEquals(1.66, response.score("severity").score(), 1e-9);
        assertEquals(QuestionType.SCORE, response.answer("severity").type());
        assertEquals(List.of("urgent", "routing", "severity"), response.names());
    }

    @Test
    @DisplayName("asking for the wrong type fails closed instead of coercing a default")
    void typeMismatchFailsClosed() {
        JevException e = assertThrows(JevException.class, () -> response.choice("urgent"));

        assertTrue(e.getMessage().contains("is noul"), e.getMessage());
        assertThrows(JevException.class, () -> response.noul("routing"));
        assertThrows(JevException.class, () -> response.score("routing"));
    }

    @Test
    @DisplayName("an unknown question name names the answers that were actually returned")
    void unknownName() {
        JevException e = assertThrows(JevException.class, () -> response.answer("nope"));

        assertTrue(e.getMessage().contains("no answer named 'nope'"), e.getMessage());
        assertTrue(e.getMessage().contains("urgent"), e.getMessage());
    }

    @Test
    @DisplayName("an answer built without its data fails closed")
    void incompleteAnswersFailClosed() {
        assertThrows(JevException.class, () -> new Answer.Choice("a", 1.0, List.of()));
        assertThrows(JevException.class, () -> new Answer.Choice(null, 1.0,
                List.of(new Answer.Choice.Outcome("a", 1.0))));
        assertThrows(JevException.class, () -> new Answer.Score(1.0, 1.0, List.of()));
    }

    @Test
    @DisplayName("usage is exposed for cost accounting")
    void usage() {
        assertEquals(380, response.usage().inputTokens());
        assertEquals(73, response.usage().outputTokens());
    }
}
