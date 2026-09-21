package com.luigivismara.jev;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests: questions must serialize exactly as the TypeSafe OpenAPI schema describes. */
class QuestionSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Compares JSON structurally, so formatting in the expected text block does not matter. */
    private void assertJson(String expected, String actual) throws Exception {
        assertEquals(mapper.readTree(expected), mapper.readTree(actual));
    }

    @Test
    @DisplayName("noul question carries only its discriminator and instructions")
    void noulMinimal() throws Exception {
        String json = mapper.writeValueAsString(Question.noul("Is this spam?"));

        assertJson("""
                {
                  "type": "noul",
                  "instructions": "Is this spam?"
                }""", json);
    }

    @Test
    @DisplayName("noul criteria serialize as the reserved words true and false")
    void noulCriteria() throws Exception {
        Question question =
                Question.noul("Is this spam?", "Unsolicited ads", "A real conversation");

        String json = mapper.writeValueAsString(question);

        assertJson("""
                {
                  "type": "noul",
                  "instructions": "Is this spam?",
                  "criteria": {
                    "true": "Unsolicited ads",
                    "false": "A real conversation"
                  }
                }""", json);
    }

    @Test
    @DisplayName("null instructions are omitted rather than sent as null")
    void omitsNulls() throws Exception {
        String json = mapper.writeValueAsString(Question.noul(null));

        assertJson("""
                {
                  "type": "noul"
                }""", json);
    }

    @Test
    @DisplayName("choice options become the criteria object the API expects")
    void choice() throws Exception {
        Question question = Question.choice("Which team?",
                Question.option("support", "General help"),
                Question.option("billing", "Payment issues"));

        String json = mapper.writeValueAsString(question);

        assertJson("""
                {
                  "type": "choice",
                  "instructions": "Which team?",
                  "criteria": {
                    "support": "General help",
                    "billing": "Payment issues"
                  }
                }""", json);
    }

    @Test
    @DisplayName("score levels become an ordered criteria array")
    void score() throws Exception {
        Question question = Question.score("How urgent?", "Can wait", "This week", "Today");

        String json = mapper.writeValueAsString(question);

        assertJson("""
                {
                  "type": "score",
                  "instructions": "How urgent?",
                  "criteria": ["Can wait", "This week", "Today"]
                }""", json);
    }

    @Test
    @DisplayName("instructions accept a structured value, not just a string")
    void structuredInstructions() throws Exception {
        record Task(String task) {
        }

        String json = mapper.writeValueAsString(
                Question.noul(new Task("Identify unsolicited advertising.")));

        assertJson("""
                {
                  "type": "noul",
                  "instructions": {
                    "task": "Identify unsolicited advertising."
                  }
                }""", json);
    }

    @Test
    @DisplayName("an option without a description is sent as an empty description")
    void optionWithoutDescription() throws Exception {
        String json = mapper.writeValueAsString(
                Question.choice(null, Question.option("support")));

        assertJson("""
                {
                  "type": "choice",
                  "criteria": {
                    "support": ""
                  }
                }""", json);
    }

    @Test
    @DisplayName("empty options or levels are rejected before a request is ever sent")
    void rejectsEmptyCriteria() {
        assertThrows(IllegalArgumentException.class, () -> Question.choice("x"));
        assertThrows(IllegalArgumentException.class, () -> Question.score("x"));
        assertThrows(IllegalArgumentException.class,
                () -> Question.choice("x", (Question.Option[]) null));
        assertThrows(IllegalArgumentException.class, () -> Question.score("x", (Object[]) null));
    }

    @Test
    @DisplayName("an option needs a name, and duplicate option names are rejected")
    void rejectsBadOptions() {
        assertThrows(IllegalArgumentException.class, () -> Question.option(" "));
        assertThrows(IllegalArgumentException.class, () -> Question.option(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> Question.choice("x",
                Question.option("a", "first"), Question.option("a", "second")));
    }

    @Test
    @DisplayName("options are exposed as a list and looked up by name")
    void optionsAreQueryable() {
        Question.Choice choice = Question.choice("Which team?",
                Question.option("support", "General help"),
                Question.option("billing", "Payment issues"));

        assertEquals(2, choice.options().size());
        assertEquals("General help", choice.option("support").description());
        assertNull(choice.option("nope"));
        assertThrows(UnsupportedOperationException.class,
                () -> choice.options().add(Question.option("x")));
    }

    @Test
    @DisplayName("each variant reports its own primitive type")
    void typeAccessors() {
        assertEquals(QuestionType.NOUL, Question.noul("x").type());
        assertEquals(QuestionType.CHOICE, Question.choice("x", Question.option("a")).type());
        assertEquals(QuestionType.SCORE, Question.score("x", "a").type());
        assertEquals("noul", QuestionType.NOUL.wireName());
    }

    @Test
    @DisplayName("named() pairs a question with the name its answer comes back under")
    void named() {
        NamedQuestion named = Question.noul("Is this urgent?").named("urgent");

        assertEquals("urgent", named.name());
        assertEquals(QuestionType.NOUL, named.question().type());
        assertThrows(IllegalArgumentException.class, () -> Question.noul("x").named(" "));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new NamedQuestion("urgent", null)).getMessage().contains("urgent"));
    }
}
