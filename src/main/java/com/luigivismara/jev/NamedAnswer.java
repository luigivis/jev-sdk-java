package com.luigivismara.jev;

/**
 * An answer paired with the question name it was filed under.
 *
 * <p>This is what {@link JevResponse#answers()} iterates, so it is where you go when handling every
 * answer the same way:
 *
 * <pre>{@code
 * for (NamedAnswer entry : response.answers()) {
 *     log.info("{} -> {}", entry.name(), switch (entry.answer()) {
 *         case Answer.Noul n   -> n.noul() > 0.5 ? "yes" : "no";
 *         case Answer.Choice c -> c.choice();
 *         case Answer.Score s  -> String.valueOf(s.nearestDescription());
 *     });
 * }
 * }</pre>
 *
 * @param name   the name from the request
 * @param answer the answer, whose variant matches the question's type
 */
public record NamedAnswer(String name, Answer answer) {

    /**
     * Validates the pair.
     *
     * @throws JevException if the name or the answer is missing
     */
    public NamedAnswer {
        if (name == null || answer == null) {
            throw new JevException("an answer arrived without a name or a value");
        }
    }
}
