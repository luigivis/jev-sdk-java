# jev-sdk-java

A small, type-safe Java 21 client for the **TypeSafe AI Jev** (System One) decision API.

Jev is a decision model, not a chat model: you hand it some content and a set of *typed questions*,
and it hands back structured answers with calibrated probabilities. This SDK models that contract
with Java's own type system — `enum`s for the parts the API fixes, sealed interfaces and records for
the parts you fill in — so a decision request that compiles is a decision request the API accepts.

> **Thanks to the TypeSafe AI team** for building Jev and for publishing a clean, well-documented
> OpenAPI schema. This is an unofficial community SDK; it is not affiliated with or endorsed by
> TypeSafe AI. All credit for the model and the API design goes to them.

---

## Contents

- [Why a typed SDK](#why-a-typed-sdk)
- [Install](#install)
- [Quick start](#quick-start)
- [Authentication](#authentication)
- [The three primitives](#the-three-primitives)
  - [Noul — yes/no](#noul--yesno)
  - [Choice — pick one](#choice--pick-one)
  - [Score — rate on a rubric](#score--rate-on-a-rubric)
- [Asking several questions at once](#asking-several-questions-at-once)
- [Describing the content](#describing-the-content)
- [Reading answers](#reading-answers)
- [Enums: what is fixed and what is not](#enums-what-is-fixed-and-what-is-not)
- [Configuring the client](#configuring-the-client)
- [Error handling](#error-handling)
- [Implementation patterns](#implementation-patterns)
- [Spring Boot integration](#spring-boot-integration)
- [Testing](#testing)
- [Javadoc](#javadoc)
- [API reference](#api-reference)
- [Design notes](#design-notes)
- [License](#license)

---

## Why a typed SDK

The raw API is a single `POST /v1/systemone`. You *can* call it with hand-built maps, and the docs
on the community site show exactly that. The problem is that the request is a discriminated union —
a `choice` question needs a name-to-description object, a `score` question needs an ordered array,
a `noul` question needs neither — and a bag of maps gives you no help getting that right. You find
out at runtime, with a 422.

This SDK moves those mistakes to compile time. There are no maps in its API at all: every
name-to-value pair is a small record, so the compiler knows what a name means and your IDE can
complete it.

```java
Question.choice("Which team?",                      // options, each a named record
        Question.option("billing", "Payment issues"),
        Question.option("support", "General help"));

Question.score("How urgent?",                       // ordered levels, first scores 0
        "Can wait", "This week", "Today");

Question.noul("Is this spam?");                     // no criteria needed
```

The same holds on the way back: an `Answer` is a sealed interface, so a `switch` over it is
exhaustive and the compiler tells you when you forgot a case, and probabilities come back as
ordered records rather than a `Map<String, Double>` you have to hope is populated.

## Install

Requires **Java 21+**. The only runtime dependency is Jackson Databind; HTTP goes through the JDK's
own `java.net.http.HttpClient`.

**Maven**

```xml
<dependency>
  <groupId>com.luigivismara</groupId>
  <artifactId>jev-sdk-java</artifactId>
  <version>0.1.0</version>
</dependency>
```

**Gradle**

```kotlin
implementation("com.luigivismara:jev-sdk-java:0.1.0")
```

Published to Maven Central, so there is no repository to add and nothing to authenticate against.

To build from source instead:

```bash
mvn install
```

## Quick start

```java
import com.luigivismara.jev.*;

try (JevClient jev = JevClient.fromEnv()) {
    JevResponse response = jev.ask(
            "My order has not arrived and I was charged twice.",
            Question.noul("Does this need attention today?").named("urgent"));

    if (response.noul("urgent").noul() > 0.8) {
        escalate();
    }
}
```

`named("urgent")` is how a question gets the name its answer comes back under. `JevClient` is
immutable and thread-safe — create one per application and reuse it, since it holds a pooled
`HttpClient` underneath.

## Authentication

The API key is never read from source. Put it in the environment:

```bash
export JEV_API_KEY="apikey_..."
```

```java
JevClient jev = JevClient.fromEnv();     // reads JEV_API_KEY, throws if unset
```

Or pass it in from your own secret manager:

```java
JevClient jev = JevClient.of(secrets.get("jev-api-key"));
```

If a key ever appears in a chat, a log, a ticket or a commit, **rotate it** in the TypeSafe console.
The SDK never logs the key and never echoes it in an exception message.

## The three primitives

Every Jev request is content (`state`) plus named questions.

### Noul — yes/no

Returns the probability that the answer is yes (or that the statement is true). Values near `0.5`
mean the model is genuinely undecided, which is information you should use.

```java
// Plain question
Question.noul("Is this message spam?");

// A statement to evaluate instead of a question
Question.noul("This message contains unsolicited advertising.");

// With explicit criteria for each side
Question.noul("Is this message spam?",
        "Unsolicited advertising",      // what counts as yes
        "A legitimate conversation");   // what counts as no
```

```java
Answer.Noul answer = response.noul("spam");
answer.noul();        // 0.98
```

### Choice — pick one

Options are records, not map entries. Each carries a name and a description of when it applies.

```java
Question.choice("Which team should handle this ticket?",
        Question.option("support",   "General product help"),
        Question.option("logistics", "Shipping, delivery and lost packages"),
        Question.option("billing",   "Charges, refunds and invoices"));
```

`Question.option("spam")` with no description is valid — the model reads the name literally — but a
one-line description measurably helps. Duplicate option names are rejected at construction.

```java
Answer.Choice answer = response.choice("routing");
answer.choice();                    // "billing"
answer.confidence();                // 0.54
answer.outcomes();                  // [Outcome[billing, 0.69], Outcome[support, 0.28], ...]
answer.probabilityOf("logistics");  // 0.03  — throws if there is no such option
answer.runnerUp();                  // Outcome[support, 0.28], or null with a single option
```

`outcomes()` comes back sorted most-likely first, so `outcomes().get(0)` is always the selected
choice. Use it rather than `choice()` alone when two options are close — a 0.51/0.49 split is a very
different situation from 0.95/0.05, and `choice()` flattens both to the same answer.

### Score — rate on a rubric

Levels are varargs, in order. The first description scores `0`, the second `1`, and so on. The
returned `score` is the probability-weighted average, so it usually lands between levels.

```java
Question.score("How urgent is this message?",
        "Can wait",                    // 0
        "Needs attention this week",   // 1
        "Needs attention today");      // 2
```

The response merges the API's two parallel objects — the legend and the probabilities — into one
ordered rubric:

```java
Answer.Score answer = response.score("urgency");
answer.score();                 // 1.66  — the expected value
answer.nearestLevel();          // 2     — rounded to a whole rubric level
answer.nearestDescription();    // "Needs attention today"
answer.levels();                // [Level[0, "Can wait", 0.01], Level[1, ..., 0.33], ...]
answer.levelAt(1).probability();// 0.33  — throws if there is no such level
```

## Asking several questions at once

One request, one billed pass over the content, several answers. This is the cheap way to use Jev —
prefer it over three separate calls.

```java
try (JevClient jev = JevClient.fromEnv()) {
    JevResponse response = jev.ask(ticket,
            Question.choice("Which team should handle this?",
                    Question.option("support",   "General product help"),
                    Question.option("logistics", "Shipping and lost packages"),
                    Question.option("billing",   "Charges and refunds")).named("routing"),
            Question.noul("Does this need attention today?").named("urgent"),
            Question.score("How severe is this for the customer?",
                    "Can wait", "Needs attention this week", "Needs attention today")
                    .named("severity"));

    route(response.choice("routing").choice());
    System.out.println("cost: " + response.usage().inputTokens() + " input tokens");
}
```

The builder is the same thing with a name-first shape, plus control over the model:

```java
JevRequest request = JevRequest.builder()
        .state(ticket)
        .model(JevModel.JEV_LATEST)
        .ask("routing",  Question.choice("Which team?", teams))
        .ask("urgent",   Question.noul("Does this need attention today?"))
        .ask("severity", Question.score("How severe?", "Low", "Medium", "High"))
        .build();

JevResponse response = jev.ask(request);
```

Names must be distinct — a collision is rejected at build time rather than silently dropping an
answer. Output tokens are free on TypeSafe's published rate card; only `usage().inputTokens()` costs
you anything.

## Describing the content

`state` is the content every question refers to. It is typed as `Object` and serialized by Jackson,
so a plain string works and so does any record or POJO you already have:

```java
record Ticket(String subject, String message, String customerTier) {}

jev.ask(new Ticket("Package never arrived", "It has been three weeks.", "gold"),
        Question.noul("Is the customer asking for a refund?").named("refund"));
```

```json
{"state": {"subject": "Package never arrived", "message": "...", "customerTier": "gold"}}
```

Passing your own domain record is the idiomatic choice: field names become part of the context the
model reads, and you skip building a throwaway structure.

## Reading answers

Two styles, both type-safe.

**Named accessors** when you know what you asked:

```java
double urgent   = response.noul("urgent").noul();
String team     = response.choice("routing").choice();
int    severity = response.score("severity").nearestLevel();
```

These fail closed. Asking `response.choice("urgent")` when `urgent` was a noul question throws
`JevException` rather than quietly giving you a default branch.

**Exhaustive switch** when you are handling answers generically:

```java
for (NamedAnswer entry : response.answers()) {
    String rendered = switch (entry.answer()) {
        case Answer.Noul n   -> n.noul() > 0.5 ? "yes" : "no";
        case Answer.Choice c -> c.choice() + " (" + c.confidence() + ")";
        case Answer.Score s  -> String.valueOf(s.nearestDescription());
    };
    log.info("{} -> {}", entry.name(), rendered);
}
```

`Answer` is sealed, so there is no `default` branch to forget to update if TypeSafe ever adds a
fourth primitive — the compiler will point at every switch that needs attention.

## Enums: what is fixed and what is not

Two things are genuinely fixed by the API, so they are enums:

```java
QuestionType.NOUL      // "noul"     — the three primitives; the API defines exactly these
QuestionType.CHOICE    // "choice"
QuestionType.SCORE     // "score"

JevModel.JEV_LATEST    // "jev-latest"  — the published, stable aliases
JevModel.JEV_PREVIEW   // "jev-preview"
```

Model *aliases* are stable, but the concrete version behind an alias is not: ask for `jev-latest`
and `response.model()` may report `jev-1.13.0`. That is expected, and it is why the response model
is a `String` and not an enum.

If TypeSafe publishes a name this release does not know about yet, there is a raw-string escape
hatch — no need to wait for an SDK update:

```java
JevRequest.builder().model("jev-2.0.0-experimental")   // raw name, bypasses the enum
```

Discover what your account can actually use:

```java
for (ModelInfo model : jev.models()) {
    System.out.println(model.name() + " — " + model.description());
}
// jev-latest  — The latest iteration of TypeSafe's System One Model: Jev
// jev-preview — A preview version of `jev-latest`: should be better in most ways
```

Everything else — question names, instructions, options, levels — is yours, so none of it is an
enum.

## Configuring the client

```java
JevClient jev = JevClient.builder()
        .apiKey(secrets.get("jev-api-key"))
        .baseUrl("https://api.typesafe.ai")   // or a gateway / a test server
        .timeout(Duration.ofSeconds(10))      // per request; default 30s
        .maxRetries(2)                        // extra attempts; default 2, 0 disables
        .retryBackoff(Duration.ofMillis(250)) // doubles per attempt; default 250ms
        .httpClient(myConfiguredHttpClient)   // optional: proxies, custom executors
        .build();
```

Retries cover `408`, `429` and `5xx` plus transport-level `IOException`s. A `4xx` that is your
fault — `401`, `422` — is raised immediately and never retried.

## Error handling

Everything that stops a call from producing a usable answer is a `JevException` (unchecked).

```java
try {
    JevResponse response = jev.ask(request);
} catch (JevException e) {
    switch (e.statusCode()) {
        case 401 -> throw new IllegalStateException("bad or revoked Jev key", e);
        case 422 -> {
            // Field-level validation errors, straight from the API
            for (var detail : e.details()) {
                log.error("invalid {}: {}", detail.loc(), detail.msg());
                // invalid [body, questions, urgency, score, criteria]: Field required
            }
            throw e;
        }
        case 429 -> {  /* already retried; back off further upstream */ }
        default  -> log.error("Jev unavailable, falling back to the manual queue", e);
    }
    routeToManualReview();   // fail closed, never guess
}
```

`statusCode()` is `0` when the failure was not an HTTP response — a timeout, a DNS failure, or a
body that did not match the decision schema.

## Implementation patterns

**Fail closed.** If a call fails or an answer is not the type you expected, route to a safe fallback
and alert. Do not coerce the failure into a default branch — that turns an outage into silently
wrong decisions. The SDK enforces this at its own boundary: a response missing `model`, `answers` or
an answer value is rejected rather than read as zeros, and a missing `noul` is never quietly read as
`0.0` — which would be a confident "no".

**Use the probabilities, not just the verdict.** A confidence floor makes uncertainty visible:

```java
Answer.Choice routing = response.choice("routing");
if (routing.confidence() < 0.7) {
    queueForHumanReview(ticket, routing.outcomes());
} else {
    route(routing.choice());
}
```

**Cache by decision identity, not prompt text.** Two requests that represent the same decision
should reuse one answer. `JevRequest` is a record of records, so it already compares structurally —
use it as the cache key directly:

```java
Cache<JevRequest, JevResponse> cache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofHours(1))
        .build();

JevResponse response = cache.get(request, jev::ask);
```

Worth it on hot paths like moderation and tool selection.

**Put Jev in front of the generative model, not beside it.** Decide the route with Jev — it is
cheap, structured and calibrated — then call a chat model only on the branch that needs prose.

## Spring Boot integration

One bean, no starter needed:

```java
@Configuration
class JevConfig {

    @Bean(destroyMethod = "close")
    JevClient jevClient(@Value("${jev.api-key}") String apiKey) {
        return JevClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(10))
                .build();
    }
}
```

```yaml
# application.yml — the value comes from the environment, never from the file
jev:
  api-key: ${JEV_API_KEY}
```

```java
@Service
class TicketRouter {

    private static final Question.Option[] TEAMS = {
            Question.option("support",   "General product help"),
            Question.option("logistics", "Shipping, delivery and lost packages"),
            Question.option("billing",   "Charges, refunds and invoices")
    };

    private final JevClient jev;

    TicketRouter(JevClient jev) {
        this.jev = jev;
    }

    Routing route(Ticket ticket) {
        JevResponse response = jev.ask(ticket,
                Question.choice("Which team should handle this?", TEAMS).named("team"),
                Question.noul("Does this need attention today?").named("urgent"));

        Answer.Choice team = response.choice("team");
        return new Routing(
                team.confidence() < 0.7 ? "manual-review" : team.choice(),
                response.noul("urgent").noul() > 0.8);
    }
}
```

## Testing

Point the client at any local server — the SDK has no hidden global state:

```java
JevClient jev = JevClient.builder()
        .apiKey("test-key")
        .baseUrl("http://localhost:" + mockServerPort)
        .maxRetries(0)
        .build();
```

The SDK's own suite covers unit (serialization against the published schema), integration (a
throwaway JDK `HttpServer` for transport, auth headers, retries and error mapping) and live
end-to-end tests:

```bash
mvn test                          # unit + integration; no network, no cost
JEV_API_KEY=... mvn test          # also runs the live tests against api.typesafe.ai
mvn test && open target/site/jacoco/index.html   # coverage report
```

The live tests are annotated `@EnabledIfEnvironmentVariable`, so they are skipped — not failed —
when no key is present. Keep them out of a shared CI job unless that job has its own key.

## Javadoc

Every public type and method carries a worked example — 57 code blocks across 21 pages, including a
package overview with a full end-to-end request.

```bash
mvn javadoc:javadoc && open target/reports/apidocs/index.html
```

The build runs `-Xdoclint:all` with `failOnWarnings`, so a broken `{@link}`, a missing `@param` or
an undocumented public member fails the build rather than rotting quietly. `mvn package` attaches
`-javadoc.jar` and `-sources.jar` alongside the main artifact, so IDE popups work for anyone who
depends on this.

Start at [`package-summary`](src/main/java/com/luigivismara/jev/package-info.java) for the overview, or
[`JevClient`](src/main/java/com/luigivismara/jev/JevClient.java) for the entry point.

## API reference

| Type | Purpose |
|---|---|
| `JevClient` | The client. `fromEnv()`, `of(key)`, `builder()`, `ask(...)`, `models()`, `close()` |
| `JevRequest` | `state` + `model` + `List<NamedQuestion>`; has a `builder()` |
| `JevResponse` | `model()`, `answers()`, `names()`, `usage()`, `noul/choice/score(name)` |
| `Question` | Sealed: `Question.Noul`, `Question.Choice`, `Question.Score`; `named(String)` |
| `Question.Option` | One option of a choice question: `name` + `description` |
| `Answer` | Sealed: `Answer.Noul`, `Answer.Choice`, `Answer.Score` |
| `Answer.Choice.Outcome` | One option and its probability, sorted most-likely first |
| `Answer.Score.Level` | One rubric level: `position` + `description` + `probability` |
| `NamedQuestion` / `NamedAnswer` | A name paired with a question or an answer |
| `QuestionType` | Enum: `NOUL`, `CHOICE`, `SCORE` |
| `JevModel` | Enum: `JEV_LATEST`, `JEV_PREVIEW` |
| `ModelInfo` | A model available to your account |
| `JevException` | Unchecked; carries `statusCode()` and `details()` |

## Design notes

Built against the live OpenAPI schema at `https://api.typesafe.ai/openapi.json` (v0.2.0), not
against third-party write-ups. Worth knowing if you are porting from another example: the widely
circulated community snippets are **out of date**. The real API requires a `model` field, and a
choice question takes `criteria` (a name-to-description object), not `options` (an array of
strings).

**No maps in the public API.** The wire format is full of JSON objects keyed by a name you chose —
questions, criteria, probabilities, the score legend. Every one of those is exposed as a record
(`NamedQuestion`, `Option`, `Outcome`, `Level`) instead of a `Map`. Maps exist only inside the
package-private methods that touch JSON, where the wire format demands them.

Deliberately not included:

- **A response cache.** Caching policy belongs to your application — see the pattern above for
  wiring Caffeine in with three lines.
- **An async API.** `ask()` is blocking. On Java 21, wrap it in a virtual thread and you have
  non-blocking concurrency without a `CompletableFuture` surface to maintain.
- **A Spring Boot starter.** One `@Bean` is less to keep in sync than a starter module.

Each of these is a ten-line addition if a real need shows up. Open an issue.

## License

MIT. See [LICENSE](LICENSE).

Jev and TypeSafe AI are products of TypeSafe AI. This SDK is an independent client and is not
affiliated with them.
