# Shadow Mode LLM Evaluator Proxy

A proxy that serves customer traffic **synchronously** from a **primary LLM** while firing an
**asynchronous shadow request** to a **candidate LLM**, comparing the two answers, and logging any
mismatches — without ever impacting the primary response.

Built with **Java 21 + Spring Boot**.

---

## 1. What it does

```
            POST /generate
                  │
                  ▼
        ┌───────────────────┐
        │   Proxy (FastAPI   │
        │   equivalent: MVC) │
        └───────────────────┘
          │                       (returns to user immediately)
          ▼
   Primary LLM ──► Adapter ──► content string ──────────────► HTTP 200 to client
          │
          └─ fire-and-forget (separate thread pool) ─┐
                                                      ▼
                                            Shadow LLM ──► Adapter ──► content string
                                                      │
                                                      ▼
                                        Semantic similarity check
                                                      │ (inconclusive)
                                                      ▼
                                        Judge LLM (prompt + both contents)
                                                      │
                                                      ▼
                                        Metrics + mismatch logging
```

- **Synchronous primary** — the user always gets the primary model's answer immediately.
- **Asynchronous shadow** — the candidate model runs in the background and the user never sees it.
- **Adapter layer** — each provider's response is reduced to a single **content string**.
- **Two-stage comparison** — a cheap **semantic similarity** check first, then a **judge LLM** only
  for inconclusive cases.
- **Mismatch logging** — structured JSON records for every disagreement.
- **Metrics** — live match / mismatch rates at `GET /metrics`.

---

## 2. Data at each step

This is the most useful section for understanding the system: it traces a single request and shows
the **exact shape of the data** at every hop. The type column shows what the data *is* at that point
(JSON envelope, plain `String`, `double`, domain object, …).

### Summary of the pipeline

| # | Step | Data type | Who produces it |
|---|------|-----------|-----------------|
| 1 | Incoming request | JSON `{request_id, prompt}` | client |
| 2 | Primary raw response | provider JSON envelope (OpenAI shape) | primary LLM / `/mock/primary` |
| 3 | Primary content | plain `String` | `ProviderAdapter` |
| 4 | Response to client | JSON `{request_id, response}` | `GenerateController` |
| 5 | Shadow task context | 3 copied `String`s | `GenerateController` → `ShadowService` |
| 6 | Shadow raw response | provider JSON envelope (Anthropic shape) | shadow LLM / `/mock/shadow` |
| 7 | Shadow content | plain `String` | `ProviderAdapter` |
| 8 | Similarity score | `double` in `[0,1]` | `SimilarityService` |
| 9 | Judge input (only if inconclusive) | system prompt + user message `String`s | `DefaultJudgeService` |
| 10 | Judge raw output | `String` containing JSON verdict | judge LLM |
| 11 | Parsed verdict | `JudgeVerdict` object | `VerdictParser` |
| 12 | Evaluation result | `EvaluationResult` record | `EvaluationService` |
| 13 | Mismatch log (only on MISMATCH) | JSON line in `mismatches.jsonl` | `MismatchLogger` |
| 14 | Metrics | JSON counters | `MetricsService` |

---

### Step 1 — Client request

The client sends a prompt. `request_id` is optional (auto-generated if omitted).

```json
{ "request_id": "req_1", "prompt": "What is the capital of France?" }
```

→ deserialized into a `GenerateRequest` object.

### Step 2 — Primary LLM raw response

`LlmClient` POSTs `{model, prompt}` to the primary endpoint and gets back the provider's **full
envelope**. With the default config the primary is OpenAI-shaped:

```json
{
  "id": "mock-primary-1",
  "model": "mock-primary-v1",
  "choices": [
    {
      "index": 0,
      "message": { "role": "assistant", "content": "The capital of France is Paris." },
      "finish_reason": "stop"
    }
  ]
}
```

At this point the answer is **buried inside** `choices[0].message.content`. Everything else
(`id`, `model`, `finish_reason`, usage, …) is noise we don't use.

### Step 3 — Adapter extracts the content string

`ProviderAdapter.extractContent("openai", raw)` reduces that envelope to **one plain string**:

```
"The capital of France is Paris."
```

This is the only thing the rest of the system cares about. Type: `String`.

### Step 4 — Response returned to the client (immediately)

The controller wraps the primary content and returns — **before** the shadow does any work:

```json
{ "request_id": "req_1", "response": "The capital of France is Paris." }
```

### Step 5 — Context handed to the shadow task

The controller calls `shadowService.fireAndForget(...)` with **copied, immutable data** (not the
request object). This is what "survives the connection closing":

```text
requestId      = "req_1"
prompt         = "What is the capital of France?"
primaryContent = "The capital of France is Paris."
```

From here everything runs on a `shadow-worker-*` thread.

### Step 6 — Shadow LLM raw response

The shadow endpoint uses a **different envelope** (Anthropic-shaped, by default) — which is exactly
why the adapter layer exists:

```json
{
  "id": "mock-shadow-1",
  "model": "mock-shadow-v1",
  "content": [
    { "type": "text", "text": "Paris is the capital of France." }
  ]
}
```

### Step 7 — Adapter extracts the shadow content string

`ProviderAdapter.extractContent("anthropic", raw)` reads `content[0].text`:

```
"Paris is the capital of France."
```

Now we have **two plain strings** to compare — regardless of which providers produced them.

### Step 8 — Stage 1: semantic similarity

`SimilarityService.similarity(primaryContent, shadowContent)` returns a `double`:

```text
primaryContent = "The capital of France is Paris."
shadowContent  = "Paris is the capital of France."
similarity     = 1.0     // same words, reordered → cosine 1.0
threshold      = 0.90
```

Because `1.0 >= 0.90`, this is a **MATCH** and the judge is **skipped** (even though the wording
differs, the meaning is identical). The result is:

```text
EvaluationResult {
  verdict        = MATCH,
  decisionSource = "semantic_similarity",
  similarity     = 1.0,
  judgeVerdict   = null            // judge never ran
}
```

Jump to Step 12.

---

### The MISMATCH path (Steps 8–11 when the answers diverge)

This uses a real scenario: the prompt **"What is the capital of Australia?"** where the two models
disagree on the fact (see scenario `factual_mismatch` in the fixtures). The two content strings are:

```text
prompt         = "What is the capital of Australia?"
primaryContent = "The capital of Australia is Canberra."
shadowContent  = "The capital of Australia is Sydney."
similarity     = 0.8333   // high lexical overlap, but below threshold
threshold      = 0.90
```

`0.8333 < 0.90`, so similarity is **inconclusive** → fall through to the judge.

#### Step 9 — Judge input

`DefaultJudgeService` builds two strings: the fixed evaluator **system prompt** and a **user
message** containing the real prompt and both answers:

```text
User prompt:
What is the capital of Australia?

Response A (primary):
The capital of Australia is Canberra.

Response B (shadow):
The capital of Australia is Sydney.
```

This is what is sent to the judge LLM.

#### Step 10 — Judge raw output

The judge returns a **string that contains JSON** (the verdict). This is the *only* model output the
system parses as JSON:

```json
{"status":"MISMATCH","winner":"primary","summary":"Different cities; Canberra is correct.","differences":[{"category":"factual","severity":"high","description":"Shadow says Sydney, which is incorrect."}]}
```

#### Step 11 — Parsed verdict

`VerdictParser.parse(...)` (tolerant of markdown fences) turns that string into a `JudgeVerdict`
object:

```text
JudgeVerdict {
  status      = "MISMATCH",
  winner      = "primary",
  summary     = "Different cities; Canberra is correct.",
  differences = [ Difference{ category="factual", severity="high",
                              description="Shadow says Sydney, which is incorrect." } ]
}
```

---

### Step 12 — Evaluation result

`EvaluationService` produces the final `EvaluationResult` record. For the mismatch path:

```text
EvaluationResult {
  verdict        = MISMATCH,
  decisionSource = "judge_llm",
  similarity     = 0.8333,
  judgeVerdict   = JudgeVerdict{ status="MISMATCH", ... }
}
```

`ShadowService` then updates metrics (`recordEvaluation(MISMATCH)`) and, because it's a mismatch,
calls the logger.

### Step 13 — Mismatch log record (only on MISMATCH)

`MismatchLogger` appends one line to `./logs/mismatches.jsonl` (and emits the same to the `MISMATCH`
logger):

```json
{
  "request_id": "req_1",
  "timestamp": "2026-06-27T05:57:24.222Z",
  "prompt": "What is the capital of Australia?",
  "primary_content": "The capital of Australia is Canberra.",
  "shadow_content": "The capital of Australia is Sydney.",
  "decision_source": "judge_llm",
  "similarity": 0.8333,
  "evaluation": {
    "status": "MISMATCH",
    "winner": "primary",
    "summary": "Different cities; Canberra is correct.",
    "differences": [
      { "category": "factual", "severity": "high", "description": "Shadow says Sydney, which is incorrect." }
    ]
  }
}
```

A MATCH writes **no** detailed record — only the metric counter moves.

### Step 14 — Metrics

`GET /metrics` reflects the running totals across all requests:

```json
{
  "total_requests": 2,
  "comparisons": 2,
  "matches": 1,
  "mismatches": 1,
  "unknown": 0,
  "primary_failures": 0,
  "shadow_failures": 0,
  "match_rate": 0.5,
  "mismatch_rate": 0.5
}
```

---

### One-line mental model

> **JSON envelope → (adapter) → string → string → (similarity) → double → (maybe judge) → verdict object → metrics + mismatch log.**
>
> The two model answers are only ever **strings**; the only JSON we parse from a model is the
> **judge's verdict**.

### Worked examples for every scenario

A full catalogue of request → primary → shadow → similarity → judge → verdict examples (exact match,
paraphrase, factual mismatch, refusal, incomplete answer, primary/shadow failure, …) lives in:

- [`docs/MOCK_SCENARIOS.md`](docs/MOCK_SCENARIOS.md) — readable scenario matrix
- [`src/main/resources/mockdata/llm-scenarios.json`](src/main/resources/mockdata/llm-scenarios.json) — machine-readable fixtures (validated by `ScenarioFixturesTest`)

---

## 3. Setup

Requires JDK 21. Maven is provided via the bundled settings, or install your own.

```bash
# from the project root
mvn spring-boot:run
```

The app starts on `http://localhost:8080` with **self-contained mock LLMs** — no API keys needed.

### Example request (matching answers → MATCH via similarity)

```bash
curl -s http://localhost:8080/generate \
  -H 'Content-Type: application/json' \
  -d '{"request_id":"req_1","prompt":"What is the capital of France?"}'
```

```json
{ "request_id": "req_1", "response": "The capital of France is Paris." }
```

### A disagreeing answer (drives the judge path → MISMATCH)

```bash
curl -s http://localhost:8080/generate \
  -H 'Content-Type: application/json' \
  -d '{"request_id":"req_5","prompt":"What is the capital of Australia?"}'
# user gets the primary answer ("...Canberra."); the shadow ("...Sydney.") is judged
# a MISMATCH in the background and written to logs/mismatches.jsonl
```

All the mapped prompts and their fixed responses are listed in
[`docs/MOCK_SCENARIOS.md`](docs/MOCK_SCENARIOS.md).

### Prove the primary stays fast

To demonstrate latency isolation without a dedicated scenario, the mock shadow sleeps 1.5s for any
prompt containing the test token `[slow]`:

```bash
time curl -s http://localhost:8080/generate \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"[slow] What is the capital of France?"}'
# returns in well under a second; the shadow keeps running in the background
```

### Metrics

```bash
curl -s http://localhost:8080/metrics
```

```json
{
  "total_requests": 3,
  "comparisons": 3,
  "matches": 2,
  "mismatches": 1,
  "unknown": 0,
  "primary_failures": 0,
  "shadow_failures": 0,
  "match_rate": 0.6667,
  "mismatch_rate": 0.3333
}
```

---

## 4. Configuration

All model wiring lives in [`src/main/resources/application.yml`](src/main/resources/application.yml)
under `app.*`. Secrets (API keys) are read from environment variables named by `api-key-env`.

```yaml
app:
  primary:
    provider: openai            # selects the adapter
    base-url: http://localhost:8080/mock/primary
    model: mock-primary-v1
    api-key-env: PRIMARY_API_KEY
  shadow:
    provider: anthropic
    base-url: http://localhost:8080/mock/shadow
    model: mock-shadow-v1
    api-key-env: SHADOW_API_KEY
  judge:
    provider: openai            # default points at the scenario-aware /mock/judge endpoint
    base-url: http://localhost:8080/mock/judge
    model: mock-judge           # (set provider: mock for the simple in-process judge)
  evaluation:
    similarity-threshold: 0.90  # similarity >= threshold → MATCH, judge skipped
```

### Switching to real LLMs

Point the providers/URLs at real APIs and supply keys via env vars:

```yaml
app:
  primary:  { provider: openai,    base-url: https://api.openai.com/v1/..., model: gpt-4-turbo, api-key-env: PRIMARY_API_KEY }
  shadow:   { provider: anthropic, base-url: https://api.anthropic.com/..., model: claude-3-5,  api-key-env: SHADOW_API_KEY }
  judge:    { provider: openai,    base-url: https://api.openai.com/v1/..., model: gpt-4o-mini, api-key-env: JUDGE_API_KEY }
```

```bash
export PRIMARY_API_KEY=sk-...
export SHADOW_API_KEY=sk-...
export JUDGE_API_KEY=sk-...
```

> The request body sent to each endpoint is the generic `{model, prompt}` shape the mocks expect.
> Wiring real provider request formats (e.g. OpenAI `messages`) is a small change in `LlmClient`;
> the **response** side already works for any provider via the adapter.

---

## 5. The adapter layer

Different providers hide the answer in different fields. The adapter ([`ProviderAdapter`](src/main/java/com/shadowproxy/adapter/ProviderAdapter.java))
returns just the content string:

| Provider  | Field read                          |
|-----------|-------------------------------------|
| openai    | `choices[0].message.content`        |
| anthropic | `content[0].text`                   |
| mock      | `output`                            |

The bundled mocks intentionally use **different shapes** (primary = OpenAI, shadow = Anthropic) so
the adapter is exercised end-to-end on every request.

---

## 6. Evaluation: similarity → judge

1. **Semantic similarity** ([`SimilarityService`](src/main/java/com/shadowproxy/evaluation/SimilarityService.java)).
   If `similarity >= similarity-threshold`, the answers are a **MATCH** and the judge is skipped.
   The default implementation is a deterministic bag-of-words cosine (offline, no keys); swap in a
   real embedding model in production by implementing the interface.
2. **Judge LLM** ([`JudgeService`](src/main/java/com/shadowproxy/evaluation/JudgeService.java)).
   For inconclusive cases the prompt + both content strings go to the judge, which returns a
   structured verdict (`MATCH` / `MISMATCH`). Parsing the judge's verdict JSON is the *only* place
   model output is parsed as JSON — the answers themselves are treated as opaque strings.

### Judge system prompt

The judge's instructions live in `SYSTEM_PROMPT` in
[`DefaultJudgeService`](src/main/java/com/shadowproxy/evaluation/DefaultJudgeService.java). It tells
the judge to compare *meaning* (not wording) and to return **only** JSON in a fixed schema:

```text
You are an impartial evaluator comparing two responses to the same user prompt.

Your task is to determine whether the responses are meaningfully equivalent.

Do not compare wording alone. Focus on:
- factual correctness
- completeness
- reasoning
- safety
- whether the user would receive the same information

Ignore minor differences such as:
- punctuation
- formatting
- synonyms
- writing style
- sentence order

If the responses are meaningfully different:
- determine which response is better
- explain why
- list the important differences

Return ONLY valid JSON matching this schema.

{
  "status": "MATCH | MISMATCH",
  "winner": "primary | shadow | tie",
  "summary": "Brief explanation",
  "differences": [
    {
      "category": "factual | reasoning | completeness | safety | formatting | style",
      "severity": "low | medium | high",
      "description": "Describe the difference."
    }
  ]
}

Do not include markdown.
Do not include code fences.
Do not include any explanation outside the JSON.
```

### Judge user message

Alongside the system prompt, the judge receives a user message with the original prompt and both
answers (built by `buildUserMessage(...)`):

```text
User prompt:
<the original user prompt>

Response A (primary):
<primary content>

Response B (shadow):
<shadow content>
```

### Judge verdict (what comes back)

The judge replies with a JSON string matching the schema above, e.g.:

```json
{
  "status": "MISMATCH",
  "winner": "primary",
  "summary": "Different cities; Canberra is correct.",
  "differences": [
    { "category": "factual", "severity": "high", "description": "Shadow says Sydney, which is incorrect." }
  ]
}
```

This string is parsed by [`VerdictParser`](src/main/java/com/shadowproxy/evaluation/VerdictParser.java)
into a `JudgeVerdict` (tolerant of accidental markdown fences). If it can't be parsed, the verdict
becomes `UNKNOWN` and is logged for debugging.

---

## 7. How the background task is decoupled

This is the core engineering requirement. See [`AppConfig`](src/main/java/com/shadowproxy/config/AppConfig.java)
and [`ShadowService`](src/main/java/com/shadowproxy/shadow/ShadowService.java).

- The shadow comparison is submitted to a **dedicated `ExecutorService`** (daemon threads), entirely
  separate from the servlet request thread.
- `GenerateController` returns the primary response **before** the shadow task is guaranteed to make
  progress, so the client connection can close while the shadow keeps running.
- The shadow task is wrapped in a top-level `try/catch`; **any** slowness, timeout, or exception is
  caught and recorded as a `shadow_failure` — it can never propagate to the user.
- The work queue is **bounded**; if it overflows, the task is dropped (recorded as a failure) rather
  than blocking the primary path.

The integration test `primaryReturnsImmediatelyEvenWhenShadowIsSlow` proves the primary returns in
under a second even though the shadow sleeps 1.5 seconds.

---

## 8. Mismatch logs

On a `MISMATCH`, [`MismatchLogger`](src/main/java/com/shadowproxy/logging/MismatchLogger.java) writes
a structured record to the `MISMATCH` logger and appends one JSON object per line to
`./logs/mismatches.jsonl`:

```json
{
  "request_id": "req_5",
  "timestamp": "2026-06-27T12:00:00Z",
  "prompt": "What is the capital of Australia?",
  "primary_content": "The capital of Australia is Canberra.",
  "shadow_content": "The capital of Australia is Sydney.",
  "decision_source": "judge_llm",
  "similarity": 0.8333,
  "evaluation": { "status": "MISMATCH", "winner": "primary", "summary": "Different cities; Canberra is correct.", "differences": [ { "category": "factual", "severity": "high", "description": "Shadow says Sydney, which is incorrect." } ] }
}
```

---

## 9. Running tests

```bash
mvn test          # unit + integration tests
mvn verify        # full build (what CI runs)
```

- **Unit tests** — adapter extraction, cosine similarity (incl. threshold boundary), judge verdict
  parsing, and the similarity→judge routing in `EvaluationService`.
- **Integration tests** — primary latency isolation, mismatch logging via the judge, match via
  similarity, input validation, and the `/metrics` endpoint.

---

## 10. Project layout

```
src/main/java/com/shadowproxy/
├── ShadowProxyApplication.java
├── config/        ProxyProperties, AppConfig (executor + RestClient)
├── controller/    GenerateController, MetricsController, MockLlmController
├── adapter/       ProviderAdapter (content-string extraction)
├── llm/           LlmClient (HTTP)
├── evaluation/    SimilarityService, JudgeService, VerdictParser, EvaluationService
├── shadow/        ShadowService (async fire-and-forget)
├── metrics/       MetricsService
├── logging/       MismatchLogger
└── model/         request/response + verdict types
```

---

## 11. API

| Method | Path             | Description                                  |
|--------|------------------|----------------------------------------------|
| POST   | `/generate`      | Serve primary response, fire shadow compare  |
| GET    | `/metrics`       | Live match / mismatch counters and rates     |
| POST   | `/mock/primary`  | Simulated OpenAI-shaped LLM                   |
| POST   | `/mock/shadow`   | Simulated Anthropic-shaped LLM                |
| POST   | `/mock/judge`    | Simulated judge (optional)                    |
