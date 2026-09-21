package com.luigivismara.jev;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests for request construction and validation. */
class JevRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Compares JSON structurally, so formatting in the expected text block does not matter. */
    private void assertJson(String expected, String actual) throws Exception {
        assertEquals(mapper.readTree(expected), mapper.readTree(actual));
    }

    @Test
    @DisplayName("the builder defaults to the jev-latest alias")
    void defaultsToLatest() {
        JevRequest request = JevRequest.builder()
                .state("hello")
                .ask("spam", Question.noul("Is this spam?"))
                .build();

        assertEquals("jev-latest", request.model());
    }

    @Test
    @DisplayName("a raw model name overrides the enum, for versions published after this release")
    void rawModelName() {
        JevRequest request = JevRequest.builder()
                .state("hello")
                .model("jev-2.0.0-experimental")
                .ask("spam", Question.noul("x"))
                .build();

        assertEquals("jev-2.0.0-experimental", request.model());
    }

    @Test
    @DisplayName("JevModel maps to its wire id")
    void enumModel() {
        JevRequest request = JevRequest.builder()
                .state("hello")
                .model(JevModel.JEV_PREVIEW)
                .ask("spam", Question.noul("x"))
                .build();

        assertEquals("jev-preview", request.model());
        assertEquals("jev-latest", JevModel.JEV_LATEST.id());
    }

    @Test
    @DisplayName("missing state, model or questions fail before any network call")
    void validation() {
        NamedQuestion question = Question.noul("x").named("a");

        assertThrows(IllegalArgumentException.class,
                () -> new JevRequest(null, JevModel.JEV_LATEST, question));
        assertThrows(IllegalArgumentException.class,
                () -> new JevRequest("s", (JevModel) null, question));
        assertThrows(IllegalArgumentException.class,
                () -> new JevRequest("s", "  ", List.of(question)));
        assertThrows(IllegalArgumentException.class,
                () -> new JevRequest("s", JevModel.JEV_LATEST));
        assertThrows(IllegalArgumentException.class,
                () -> JevRequest.builder().state("s").build());
    }

    @Test
    @DisplayName("two questions sharing a name are rejected instead of silently colliding")
    void rejectsDuplicateNames() {
        assertThrows(IllegalArgumentException.class, () -> new JevRequest("s", JevModel.JEV_LATEST,
                Question.noul("first").named("a"), Question.noul("second").named("a")));
    }

    @Test
    @DisplayName("questions are copied, so the builder cannot leak a mutable list")
    void questionsAreImmutable() {
        JevRequest.Builder builder = JevRequest.builder().state("s").ask("a", Question.noul("x"));
        JevRequest request = builder.build();
        builder.ask("b", Question.noul("y"));

        assertEquals(List.of("a"), request.questions().stream().map(NamedQuestion::name).toList());
        assertThrows(UnsupportedOperationException.class,
                () -> request.questions().add(Question.noul("z").named("c")));
    }

    @Test
    @DisplayName("reusing a name in the builder replaces the earlier question in place")
    void duplicateNameReplaces() {
        JevRequest request = JevRequest.builder()
                .state("s")
                .ask("a", Question.noul("first"))
                .ask("b", Question.noul("other"))
                .ask("a", Question.noul("second"))
                .build();

        assertEquals(2, request.questions().size());
        assertEquals("second", ((Question.Noul) request.question("a")).instructions());
        assertEquals(List.of("b", "a"),
                request.questions().stream().map(NamedQuestion::name).toList());
        assertThrows(IllegalArgumentException.class, () -> request.question("nope"));
    }

    @Test
    @DisplayName("a mixed request serializes to the exact payload the API accepts")
    void fullPayload() throws Exception {
        JevRequest request = JevRequest.builder()
                .state("My order has not arrived")
                .model(JevModel.JEV_LATEST)
                .ask("routing", Question.choice("Which team?",
                        Question.option("support", "General help"),
                        Question.option("logistics", "Shipping issues")))
                .ask("urgent", Question.noul("Is this urgent?"))
                .ask("severity", Question.score("How severe?", "Low", "High"))
                .build();

        String json = mapper.writeValueAsString(request);

        assertJson("""
                {
                  "state": "My order has not arrived",
                  "model": "jev-latest",
                  "questions": {
                    "routing": {
                      "type": "choice",
                      "instructions": "Which team?",
                      "criteria": {
                        "support": "General help",
                        "logistics": "Shipping issues"
                      }
                    },
                    "urgent": {
                      "type": "noul",
                      "instructions": "Is this urgent?"
                    },
                    "severity": {
                      "type": "score",
                      "instructions": "How severe?",
                      "criteria": ["Low", "High"]
                    }
                  }
                }""", json);
    }

    @Test
    @DisplayName("state may be any serializable value, such as a record")
    void structuredState() throws Exception {
        record Ticket(String subject, String message) {
        }

        JevRequest request = JevRequest.builder()
                .state(new Ticket("Duplicate charge", "I was charged twice."))
                .ask("billing", Question.noul("Is this about billing?"))
                .build();

        assertJson("""
                {
                  "state": {
                    "subject": "Duplicate charge",
                    "message": "I was charged twice."
                  },
                  "model": "jev-latest",
                  "questions": {
                    "billing": {
                      "type": "noul",
                      "instructions": "Is this about billing?"
                    }
                  }
                }""", mapper.writeValueAsString(request));
    }

    @Test
    @DisplayName("varargs and list constructors build the same request")
    void varargsMatchesList() {
        NamedQuestion question = Question.noul("x").named("a");

        assertEquals(new JevRequest("s", JevModel.JEV_LATEST, List.of(question)),
                new JevRequest("s", JevModel.JEV_LATEST, question));
    }
}
