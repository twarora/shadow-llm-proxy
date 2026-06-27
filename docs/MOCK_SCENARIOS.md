# Mock LLM Scenarios

A catalogue of example data showing how a request flows through the proxy under different
conditions. The machine-readable source is
[`src/main/resources/mockdata/llm-scenarios.json`](../src/main/resources/mockdata/llm-scenarios.json),
and `ScenarioFixturesTest` validates that every comparison scenario's `judge_invoked` flag agrees
with the real `CosineSimilarityService` (so this data can't drift from actual behavior).

- **Similarity threshold:** `0.90`
- **Primary envelope:** OpenAI shape (`choices[0].message.content`)
- **Shadow envelope:** Anthropic shape (`content[0].text`)
- Similarity scores below are from the bundled **lexical** cosine. A real embedding model would
  produce different absolute numbers (especially for paraphrases) but the same routing intent.

## Scenario matrix

| # | Scenario | Prompt | Primary content | Shadow content | Similarity | Judge? | Final verdict | Source |
|---|----------|--------|-----------------|----------------|-----------:|--------|---------------|--------|
| 1 | Identical answers | What is the capital of France? | `The capital of France is Paris.` | `The capital of France is Paris.` | 1.00 | no | **MATCH** | similarity |
| 2 | Same answer, reordered | Name the capital city of France. | `The capital of France is Paris.` | `Paris is the capital of France.` | 1.00 | no | **MATCH** | similarity |
| 3 | Formatting only | What is 6 × 7? | `Answer: 42` | `42` | 0.71 | yes | **MATCH** | judge (formatting, low) |
| 4 | Genuine paraphrase | What is recursion? | `Recursion is when a function calls itself.` | `A function that invokes itself is using recursion.` | 0.67 | yes | **MATCH** | judge |
| 5 | Factual disagreement | Capital of Australia? | `...is Canberra.` | `...is Sydney.` | 0.83 | yes | **MISMATCH** | judge (factual, high) |
| 6 | Shadow refuses | How do I reset my password? | `You can reset your password in account settings.` | `I'm sorry, but I can't help with that request.` | 0.10 | yes | **MISMATCH** | judge (safety, high) |
| 7 | Less complete | At what temperature does water boil? | `...100 degrees Celsius at sea level.` | `...100 degrees.` | 0.81 | yes | **MISMATCH** | judge (completeness, medium) |
| 8 | Judge unparseable | When is the meeting? | `The meeting is at 3 PM.` | `The meeting starts at three in the afternoon.` | 0.52 | yes | **UNKNOWN** | judge (bad output) |
| 9 | Slow shadow (1.5s) | Run the slow diagnostic check. | `Diagnostic complete: all systems normal.` | same (delayed 1.5s) | 1.00 | no | **MATCH** | similarity (primary unaffected) |
| 10 | Primary failure | Summarize the quarterly report. | — (HTTP 503) | — | — | — | **PRIMARY_FAILED** | shadow skipped |
| 11 | Shadow failure | Give me today's weather. | `It is sunny and 24 degrees.` | — (timeout) | — | — | — | `shadow_failure` |

## How to read the routing

```
similarity >= 0.90 ?
   ├─ yes → MATCH        (decision_source = semantic_similarity, judge skipped)   ── scenarios 1, 2, 9
   └─ no  → call judge
              ├─ MATCH                      ── scenarios 3, 4
              ├─ MISMATCH → mismatch log    ── scenarios 5, 6, 7
              └─ unparseable → UNKNOWN       ── scenario 8

primary fails  → PRIMARY_FAILED, no shadow                                        ── scenario 10
shadow fails   → shadow_failure, user already served                              ── scenario 11
```

## Coverage at a glance

| Path exercised | Scenarios |
|----------------|-----------|
| MATCH via similarity (judge skipped) | 1, 2, 9 |
| MATCH via judge | 3, 4 |
| MISMATCH via judge (logged) | 5, 6, 7 |
| UNKNOWN (judge unparseable) | 8 |
| Latency isolation (slow shadow) | 9 |
| Primary failure (shadow skipped) | 10 |
| Shadow failure (isolated) | 11 |
| Difference categories | factual, safety, completeness, formatting |
| Severities | low, medium, high |

## Using the fixtures

These scenarios are **wired into the live mock endpoints** (`ScenarioStore` + `MockLlmController`),
so sending a scenario's prompt drives its mapped primary response, shadow response, and judge
verdict end-to-end:

```bash
# Scenario 1 — identical → MATCH via similarity (judge skipped)
curl -s localhost:8080/generate -H 'Content-Type: application/json' \
  -d '{"prompt":"What is the capital of France?"}'

# Scenario 5 — factual diff → judge → MISMATCH (logged to logs/mismatches.jsonl)
curl -s localhost:8080/generate -H 'Content-Type: application/json' \
  -d '{"prompt":"What is the capital of Australia?"}'

# Scenario 9 — primary failure → HTTP 502 PRIMARY_FAILED, shadow skipped
curl -i -s localhost:8080/generate -H 'Content-Type: application/json' \
  -d '{"prompt":"Summarize the quarterly report."}'

curl -s localhost:8080/metrics
```

How the wiring works:

- `/mock/primary` and `/mock/shadow` look up the prompt and return the scenario's mapped envelope
  (or simulate an error for the failure scenarios).
- `/mock/judge` receives the prompt + both contents and returns the scenario's **mapped verdict**
  (matched by the contents present in the judge message) — so judge-decided scenarios produce their
  documented MATCH / MISMATCH / UNKNOWN result. The default `app.judge.provider` points here.
- Prompts not in the fixtures fall back to a default answer returned identically by primary and
  shadow (so they resolve to a MATCH).

Also used:

- **As documentation:** the JSON shows the exact envelope shapes and the verdict schema per case.
- **As test data:** `ScenarioFixturesTest` loads the file and asserts the similarity routing;
  the integration tests drive several scenarios over real HTTP.
- **To extend:** add a new object to the `scenarios` array with a **unique prompt**. If it has both
  `primary_content` and `shadow_content`, `ScenarioFixturesTest` will automatically check that its
  `similarity.judge_invoked` flag is consistent with the computed score.
