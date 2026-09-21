/**
 * A type-safe Java client for the TypeSafe AI Jev (System One) decision API.
 *
 * <h2>What Jev is</h2>
 *
 * Jev is a decision model, not a chat model. You send it some content — the
 * {@linkplain com.luigivismara.jev.JevRequest#state() state} — together with a set of named, typed
 * questions, and it returns structured answers with calibrated probabilities. There is no prose to
 * parse and no prompt to tune.
 *
 * <h2>The three primitives</h2>
 *
 * Every question is one of three shapes, and each has a matching answer:
 *
 * <table class="striped">
 *   <caption>Question primitives and the answers they produce</caption>
 *   <thead>
 *     <tr><th>Question</th><th>Asks</th><th>Answer</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr>
 *       <td>{@link com.luigivismara.jev.Question.Noul}</td>
 *       <td>a yes/no judgement</td>
 *       <td>{@link com.luigivismara.jev.Answer.Noul} — a probability of yes</td>
 *     </tr>
 *     <tr>
 *       <td>{@link com.luigivismara.jev.Question.Choice}</td>
 *       <td>pick one of your options</td>
 *       <td>{@link com.luigivismara.jev.Answer.Choice} — the pick, plus the whole distribution</td>
 *     </tr>
 *     <tr>
 *       <td>{@link com.luigivismara.jev.Question.Score}</td>
 *       <td>rate against an ordered rubric</td>
 *       <td>{@link com.luigivismara.jev.Answer.Score} — an expected score and the rubric</td>
 *     </tr>
 *   </tbody>
 * </table>
 *
 * <h2>A complete example</h2>
 *
 * Route a support ticket, flag it for urgency and rate its severity — one request, one billed pass
 * over the content:
 *
 * <pre>{@code
 * record Ticket(String subject, String message, String customerTier) {}
 *
 * Ticket ticket = new Ticket(
 *         "Package never arrived",
 *         "My order has not arrived and I was charged twice.",
 *         "gold");
 *
 * try (JevClient jev = JevClient.fromEnv()) {
 *     JevResponse response = jev.ask(ticket,
 *             Question.choice("Which team should handle this?",
 *                     Question.option("support",   "General product help"),
 *                     Question.option("logistics", "Shipping, delivery and lost packages"),
 *                     Question.option("billing",   "Charges, refunds and invoices"))
 *                     .named("routing"),
 *             Question.noul("Does this need attention today?").named("urgent"),
 *             Question.score("How severe is this for the customer?",
 *                     "Can wait", "Needs attention this week", "Needs attention today")
 *                     .named("severity"));
 *
 *     Answer.Choice routing = response.choice("routing");
 *     if (routing.confidence() < 0.7) {
 *         queueForHumanReview(ticket, routing.outcomes());
 *     } else {
 *         assignTo(routing.choice());
 *     }
 *
 *     if (response.noul("urgent").noul() > 0.8) {
 *         page(response.score("severity").nearestDescription());
 *     }
 * }
 * }</pre>
 *
 * <h2>No maps</h2>
 *
 * The wire format is full of JSON objects keyed by a name you chose: questions, criteria,
 * probabilities, the score legend. This package exposes every one of them as a record —
 * {@link com.luigivismara.jev.NamedQuestion}, {@link com.luigivismara.jev.Question.Option},
 * {@link com.luigivismara.jev.Answer.Choice.Outcome}, {@link com.luigivismara.jev.Answer.Score.Level} — so the
 * compiler knows what each name means and your IDE can complete it. Maps appear only in the
 * package-private methods that touch JSON.
 *
 * <h2>Failing closed</h2>
 *
 * A decision you cannot trust is worse than no decision. Every boundary in this package rejects
 * ambiguity instead of guessing:
 *
 * <ul>
 *   <li>A response missing {@code model}, {@code answers} or {@code usage} is rejected, not read
 *       as nulls.</li>
 *   <li>A missing {@code noul} value is rejected, not read as {@code 0.0} — which would be a
 *       confident "no".</li>
 *   <li>Asking for the wrong answer type throws instead of returning a default branch.</li>
 *   <li>Duplicate question names and duplicate option names are rejected when you build the
 *       request, not silently collapsed by the server.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * {@link com.luigivismara.jev.JevClient} is immutable and safe to share; create one per application. Every
 * other type in this package is a record or an enum, so all of them are immutable too.
 *
 * @see <a href="https://api.typesafe.ai/openapi.json">The TypeSafe AI OpenAPI schema</a>
 */
package com.luigivismara.jev;
