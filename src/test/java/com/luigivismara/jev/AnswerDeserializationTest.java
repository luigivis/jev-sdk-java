package com.luigivismara.jev;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests: answers parse from real payloads captured from api.typesafe.ai. */
class AnswerDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    @DisplayName("the type discriminator selects the noul variant")
    void noul() throws Exception {
        Answer answer = mapper.readValue("""
                {"type": "noul", "noul": 0.84}""", Answer.class);

        Answer.Noul noul = assertInstanceOf(Answer.Noul.class, answer);
        assertEquals(0.84, noul.noul(), 1e-9);
        assertEquals(QuestionType.NOUL, noul.type());
    }

    @Test
    @DisplayName("choice probabilities become outcomes ordered from most to least likely")
    void choice() throws Exception {
        Answer.Choice choice = assertInstanceOf(Answer.Choice.class, mapper.readValue("""
                {
                  "type": "choice",
                  "choice": "billing",
                  "confidence": 0.54,
                  "probabilities": {"support": 0.28, "billing": 0.69, "logistics": 0.03}
                }""", Answer.class));

        assertEquals("billing", choice.choice());
        assertEquals(0.54, choice.confidence(), 1e-9);
        assertEquals(List.of("billing", "support", "logistics"),
                choice.outcomes().stream().map(Answer.Choice.Outcome::choice).toList());
        assertEquals(0.69, choice.outcomes().get(0).probability(), 1e-9);
        assertEquals(0.28, choice.probabilityOf("support"), 1e-9);
        assertEquals("support", choice.runnerUp().choice());
    }

    @Test
    @DisplayName("asking for an option the answer does not have fails instead of returning zero")
    void unknownOption() throws Exception {
        Answer.Choice choice = (Answer.Choice) mapper.readValue("""
                {"type": "choice", "choice": "a", "confidence": 1.0,
                 "probabilities": {"a": 1.0}}""", Answer.class);

        assertThrows(JevException.class, () -> choice.probabilityOf("b"));
        assertNull(choice.runnerUp(), "a single-option answer has no runner-up");
    }

    @Test
    @DisplayName("the score legend and probabilities merge into one ordered rubric")
    void score() throws Exception {
        Answer.Score score = assertInstanceOf(Answer.Score.class, mapper.readValue("""
                {
                  "type": "score",
                  "score": 1.66,
                  "confidence": 0.49,
                  "legend": {"0": "Can wait", "1": "This week", "2": "Today"},
                  "probabilities": {"0": 0.01, "1": 0.33, "2": 0.66}
                }""", Answer.class));

        assertEquals(1.66, score.score(), 1e-9);
        assertEquals(2, score.nearestLevel());
        assertEquals("Today", score.nearestDescription());
        assertEquals(3, score.levels().size());
        assertEquals(0, score.levels().get(0).position());
        assertEquals("This week", score.levelAt(1).description());
        assertEquals(0.66, score.levelAt(2).probability(), 1e-9);
        assertThrows(JevException.class, () -> score.levelAt(9));
    }

    @Test
    @DisplayName("a non-numeric rubric key fails closed rather than being guessed at")
    void nonNumericRubricKey() {
        assertThrows(Exception.class, () -> mapper.readValue("""
                {"type": "score", "score": 1.0, "confidence": 0.5,
                 "legend": {"low": "Can wait"}, "probabilities": {"low": 1.0}}""", Answer.class));
    }

    @Test
    @DisplayName("a full response maps usage from snake_case and files answers by question name")
    void fullResponse() throws Exception {
        JevResponse response = mapper.readValue("""
                {
                  "model": "jev-1.13.0",
                  "answers": {
                    "routing": {
                      "type": "choice",
                      "choice": "billing",
                      "confidence": 0.54,
                      "probabilities": {"billing": 0.69, "support": 0.31}
                    },
                    "is_urgent": {"type": "noul", "noul": 0.84}
                  },
                  "usage": {"input_tokens": 380, "output_tokens": 73}
                }""", JevResponse.class);

        assertEquals("jev-1.13.0", response.model());
        assertEquals(2, response.answers().size());
        assertEquals(380, response.usage().inputTokens());
        assertEquals(73, response.usage().outputTokens());
        assertEquals("billing", response.choice("routing").choice());
        assertEquals(List.of("routing", "is_urgent"), response.names());
    }

    @Test
    @DisplayName("unknown fields from a newer API version do not break parsing")
    void toleratesUnknownFields() throws Exception {
        Answer answer = mapper.readValue("""
                {"type": "noul", "noul": 0.5, "rationale": "something new"}""", Answer.class);

        assertInstanceOf(Answer.Noul.class, answer);
    }

    @Test
    @DisplayName("a missing answer value is rejected instead of defaulting to zero")
    void missingValueFailsClosed() {
        assertThrows(Exception.class, () -> mapper.readValue("""
                {"type": "noul"}""", Answer.class));
        assertThrows(Exception.class, () -> mapper.readValue("""
                {"type": "choice", "choice": "a", "confidence": 1.0}""", Answer.class));
        assertThrows(Exception.class, () -> mapper.readValue("""
                {"type": "score", "score": 1.0, "confidence": 0.5}""", Answer.class));
    }

    @Test
    @DisplayName("an unknown answer type is rejected rather than silently dropped")
    void unknownTypeIsRejected() {
        assertThrows(Exception.class, () -> mapper.readValue("""
                {"type": "vibes", "vibes": 1}""", Answer.class));
    }

    @Test
    @DisplayName("a response body missing model, answers or usage fails closed")
    void incompleteResponseFailsClosed() {
        assertThrows(Exception.class, () -> mapper.readValue("""
                {"unexpected": true}""", JevResponse.class));
        assertThrows(Exception.class, () -> mapper.readValue("""
                {"model": "jev-latest", "answers": {},
                 "usage": {"input_tokens": 1, "output_tokens": 1}}""", JevResponse.class));
        assertThrows(JevException.class, () -> new JevResponse("m", List.of(), null));
        assertThrows(JevException.class, () -> new NamedAnswer("a", null));
    }

    @Test
    @DisplayName("the sealed hierarchy switches exhaustively without a default branch")
    void exhaustiveSwitch() throws Exception {
        Answer answer = mapper.readValue("""
                {"type": "noul", "noul": 0.9}""", Answer.class);

        String label = switch (answer) {
            case Answer.Noul n -> n.noul() > 0.8 ? "yes" : "no";
            case Answer.Choice c -> c.choice();
            case Answer.Score s -> "level " + s.nearestLevel();
        };

        assertEquals("yes", label);
        assertEquals("urgent", answer.named("urgent").name());
    }
}
