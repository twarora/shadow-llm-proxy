# PLAN.md — Shadow Mode LLM Evaluator Proxy

## 1. Objective

Build a proxy API that:

- Serves user requests **synchronously** using a **Primary LLM**
- Fires the **same prompt** to a **Shadow (Candidate) LLM** **asynchronously**
- **Normalizes provider-specific responses** through an **adapter layer** that returns a single **content string**
- Compares the two content strings with a **semantic similarity check first**, then falls back to a **Judge LLM** only when similarity is inconclusive
- **Logs mismatches** with structured JSON payloads
- Exposes **real-time metrics** (match rate, mismatch rate)
- Guarantees shadow execution **never impacts primary latency or user-facing errors**

---

## 2. High-Level Architecture

```
Client
  │
  ▼
API Server (FastAPI)
  │
  ├──────────────────────────────┐
  │                              │
  ▼                              ▼
Primary LLM (SYNC)         Background Shadow Task (ASYNC)
  │                              │
  ▼                              ▼
Adapter (content string)   Shadow LLM Call
  │                              │
  │                              ▼
  │                        Adapter (content string)
  │                              │
  │                              ▼
  │                        Semantic Similarity Check
  │                              │ (inconclusive)
  │                              ▼
  │                        Judge LLM (prompt + both contents)
  │                              │
  │                              ▼
  │                        Logging + Metrics Store
  │
  ▼
Response returned immediately to client
```

### Request lifecycle

1. Client sends prompt to `POST /generate`
2. Proxy calls **Primary LLM** (await) → **adapter** returns its content string → return content to client **immediately**
3. Proxy schedules shadow work **without awaiting** (fire-and-forget)
4. Shadow task calls **Shadow LLM** with the **same prompt** → **adapter** returns its content string
5. Run **semantic similarity** on the two content strings → if similarity ≥ threshold, mark **MATCH** and skip the judge
6. Otherwise send **user prompt + primary content + shadow content** to the **Judge LLM** → get verdict
7. Update metrics; log full payload **only on mismatch**

---

## 3. Core Design Principles

| Principle | Detail |
|---|---|
| **Sync primary** | Blocking, latency-critical, user-facing |
| **Async shadow** | Fire-and-forget; decoupled from HTTP connection lifecycle |
| **Failure isolation** | Shadow/candidate failures NEVER affect primary response |
| **Same prompt** | Both models receive identical input |
| **Provider-agnostic** | An adapter layer reduces each provider's response to a single content string |
| **Cheap-first comparison** | Semantic similarity filters out clear matches before paying for a judge call |
| **LLM-as-judge** | Inconclusive cases are delegated to a judge LLM given the prompt + both contents |
| **Split logging** | Lightweight metrics for all requests; detailed logs for mismatches only |
| **Config-driven** | Primary/shadow/judge providers + models swappable via config (mocks locally, real LLMs in prod) |

---

## 4. API Design

### Main endpoint

```
POST /generate
```

**Request:**

```json
{
  "request_id": "req_123",
  "prompt": "What is recursion?"
}
```

**Response (success):**

```json
{
  "request_id": "req_123",
  "response": "A function calling itself"
}
```

**Response (primary failure):**

```json
{
  "error": "PRIMARY_FAILED",
  "message": "Unable to generate response"
}
```

### Metrics endpoint (stretch / next step)

```
GET /metrics
```

**Response:**

```json
{
  "total_requests": 1000,
  "matches": 950,
  "mismatches": 50,
  "primary_failures": 2,
  "shadow_failures": 3,
  "match_rate": 0.95,
  "mismatch_rate": 0.05
}
```

### Mock endpoints (local dev / tests)

```
POST /mock/primary    → fast, stable response
POST /mock/shadow     → optionally slower or intentionally different
```

---

## 5. Primary Flow (Synchronous)

```
1. Receive request
2. Validate input (request_id, prompt)
3. Call Primary LLM (await, with timeout)
4. Adapter returns content string
5. Return primary content to client immediately
6. If primary succeeded → schedule background shadow task with captured context
7. If primary failed → return error, do NOT trigger shadow
```

**Captured context passed to shadow task (copies, not request-scoped objects):**

```json
{
  "request_id": "req_123",
  "prompt": "...",
  "primary_content": "..."
}
```

---

## 6. Shadow Flow (Asynchronous)

```
1. Load model config (already loaded at startup)
2. Call Shadow LLM with same prompt
3. Adapter returns shadow content string
4. Compute semantic similarity between primary_content and shadow_content
5. If similarity ≥ threshold → MATCH (skip judge)
6. Else send (prompt, primary_content, shadow_content) to the Judge LLM and parse verdict
7. Update in-memory metrics counters
8. Write lightweight log entry (all requests)
9. If MISMATCH → write detailed mismatch log
```

### Decoupling strategy (MVP)

- Use `asyncio.create_task(...)` at app level — **not** tied to client connection
- Wrap shadow work in top-level try/except; errors logged, never propagated
- Pass primitive copies of data into the task
- Store metrics/logs in app-level singleton (in-memory for v1)

### Production upgrade path

- Replace in-process tasks with **Redis Queue / SQS / Kafka**
- Dedicated worker service processes shadow jobs independently

---

## 7. Provider Adapter Layer

Different LLM providers put the answer text in **different fields** of their response. The adapter layer's **only job** is to dig out that one **content string**, so the rest of the system only ever deals with a plain string. Nothing else from the response is used.

### The problem it solves

| Provider | Where the content lives |
|---|---|
| **OpenAI** | `choices[0].message.content` |
| **Anthropic** | `content[0].text` |
| **Mock / custom** | `output` (or whatever the mock defines) |

Without normalization, the comparison code would need provider-specific branching everywhere. The adapter isolates that knowledge in **one place**, driven by the `provider` field in config.

### Output: just the content string

Every adapter returns a single `str` — the model's answer text. No metadata, no usage, no latency, no envelope.

```python
extract_content(provider, raw_response) -> str
```

### Example

For the OpenAI-style response:

```json
{
  "choices": [
    { "message": { "role": "assistant", "content": "Recursion is when a function calls itself." } }
  ]
}
```

the adapter returns exactly:

```
"Recursion is when a function calls itself."
```

### Per-provider extraction

```python
def extract_content(provider: str, raw: dict) -> str:
    if provider == "openai":
        return raw["choices"][0]["message"]["content"]
    if provider == "anthropic":
        return raw["content"][0]["text"]
    if provider == "mock":
        return raw["output"]
    raise ValueError(f"unknown provider: {provider}")
```

### Where it fits

```
Raw provider response
        │
        ▼
extract_content(provider, raw)     ← dig out the content string
        │
        ▼
   content string  ───────────────► sent to Judge LLM (with prompt + other content)
```

### Design notes

- **Selected by config:** the `provider` field on each of `primary` / `shadow` picks the adapter.
- **Content only:** the adapter returns just the answer string — usage, tokens, latency, and other envelope fields are ignored (out of scope).
- **Single responsibility:** adapters only extract the string — they do **not** compare. Comparison is the judge LLM's job.
- **Extensible:** adding a new provider = adding one branch/adapter, no changes to the judge step.
- **Primary and shadow can differ:** e.g. primary = OpenAI, shadow = Anthropic; each uses its own adapter.

---

## 8. Model Configuration

Configuration is **centralized** — not passed per request. The `provider` field selects which adapter is used. The **judge** is itself an LLM and has its own config block.

### `config.yaml` (non-secrets)

```yaml
primary:
  provider: mock              # mock | openai | anthropic | custom
  base_url: http://localhost:8000/mock/primary
  model: mock-primary-v1
  timeout_seconds: 30

shadow:
  provider: mock
  base_url: http://localhost:8000/mock/shadow
  model: mock-shadow-v1
  timeout_seconds: 60         # may be slower; must not block primary

evaluation:
  similarity_threshold: 0.90  # sim >= threshold → MATCH, skip judge
  embedding_provider: openai
  embedding_model: text-embedding-3-small

judge:
  provider: openai            # the evaluator LLM (fallback when similarity is inconclusive)
  base_url: https://api.openai.com/v1
  model: gpt-4o-mini
  timeout_seconds: 60

shadow_mode:
  enabled: true
  log_mismatches: true
  mismatch_log_path: ./logs/mismatches.jsonl

server:
  host: 0.0.0.0
  port: 8000
```

### `.env` (secrets — never commit)

```env
PRIMARY_API_KEY=sk-...
SHADOW_API_KEY=sk-...
JUDGE_API_KEY=sk-...
```

### Environment switching

| Environment | Primary | Shadow | Judge |
|---|---|---|---|
| **Local / CI** | Mock | Mock | Mock / stubbed verdict |
| **Production** | Real LLM | Real LLM | Real LLM (e.g. `gpt-4o-mini`) |

Proxy logic is identical; only config (provider + model) changes.

---

## 9. Evaluation: Semantic Similarity → Judge LLM

Comparison runs in **two stages**, cheapest first. There is **no JSON extraction or string parsing of the model answers** — both stages operate on the raw content strings (plus the prompt for the judge).

### Stage 1 — Semantic similarity (cheap pre-filter)

- Embed both content strings (embedding model from config) and compute **cosine similarity**.
- If **similarity ≥ `similarity_threshold`** → **MATCH**, skip the judge (no judge cost).
- If **similarity < threshold** → inconclusive, fall through to the judge.

This catches the common case where both models say the same thing in different words, without paying for a judge call every request.

### Stage 2 — Judge LLM (fallback)

- Inputs: **user prompt + primary content + shadow content**.
- The judge decides whether the two answers are meaningfully equivalent and returns a structured verdict.

### Flow

```
primary_content + shadow_content
        │
        ▼
Stage 1: Semantic Similarity
        │
   ┌────┴───────────────┐
 sim ≥ threshold      sim < threshold
   │                      │
 MATCH                    ▼
(skip judge)      Stage 2: Judge LLM (prompt + both contents)
                          │
                          ▼
                   verdict → MATCH or MISMATCH
                          │
                     ┌────┴─────────┐
                   MATCH          MISMATCH
                     │                │
                 (count)      write detailed mismatch log + count
```

### Decision rules

| Condition | Action | decision_source |
|---|---|---|
| Primary fails | Return error; **skip shadow** | — |
| Shadow fails | Log shadow failure; **primary unaffected** | — |
| Similarity ≥ threshold | MATCH (no judge call) | `semantic_similarity` |
| Judge returns `MATCH` | MATCH | `judge_llm` |
| Judge returns `MISMATCH` | MISMATCH → detailed log | `judge_llm` |
| Judge fails / unparseable verdict | Verdict = `UNKNOWN`; log for debugging | `judge_llm` |

---

## 10. Judge LLM Prompt Contract

The judge receives the user prompt, the primary content, and the shadow content.

### System / evaluator prompt

```
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

### Judge response schema

```json
{
  "status": "MATCH",
  "winner": "tie",
  "summary": "Both responses convey the same definition of recursion.",
  "differences": []
}
```

> Note: the **only** JSON parsing in the system is parsing the **judge's own verdict** above. The primary/shadow answers themselves are never parsed — they are passed to the judge as raw strings.

---

## 11. Logging Strategy

### Lightweight log (ALL requests — metrics only)

```json
{
  "request_id": "req_123",
  "timestamp": "2026-06-27T12:00:00Z",
  "verdict": "MATCH",
  "decision_source": "judge_llm"
}
```

### Detailed mismatch log (MISMATCH only)

```json
{
  "request_id": "req_123",
  "timestamp": "2026-06-27T12:00:00Z",
  "prompt": "What is recursion?",
  "primary_content": "...",
  "shadow_content": "...",
  "evaluation": {
    "status": "MISMATCH",
    "winner": "primary",
    "summary": "Shadow omits the base-case condition.",
    "differences": [
      {
        "category": "completeness",
        "severity": "medium",
        "description": "Shadow does not mention the stopping condition."
      }
    ]
  }
}
```

---

## 12. Error Handling

| Failure | Behavior |
|---|---|
| **Primary LLM fails** | Return `PRIMARY_FAILED` to client; do not run shadow |
| **Shadow LLM fails** | Log error; increment `shadow_failures`; client unaffected |
| **Adapter cannot find content** | Log normalization error with raw response; treat shadow as failed (primary unaffected) |
| **Judge LLM fails / bad verdict** | Verdict = `UNKNOWN`; log for debugging |
| **Client disconnects** | Shadow task continues (decoupled context) |

---

## 13. Project Structure

```
/workspaces/shadow-llm-proxy/
├── app/
│   ├── main.py              # FastAPI app, routes
│   ├── proxy.py             # sync primary + schedule shadow
│   ├── shadow.py            # background task logic
│   ├── adapters.py          # provider adapter layer (content string only)
│   ├── similarity.py        # embeddings + cosine similarity pre-filter
│   ├── judge.py             # judge LLM call + verdict parsing
│   ├── mocks.py             # mock primary/shadow handlers
│   ├── metrics.py           # counters + GET /metrics
│   └── config.py            # load config.yaml + .env
├── tests/
│   ├── unit/
│   │   ├── test_adapters.py
│   │   ├── test_similarity.py
│   │   └── test_judge.py
│   └── integration/
│       ├── test_primary_latency.py
│       └── test_shadow_isolation.py
├── .github/workflows/ci.yml
├── config.yaml
├── .env.example
├── requirements.txt
├── README.md
└── PLAN.md
```

---

## 14. Testing Strategy

### Unit tests

| Test | Validates |
|---|---|
| OpenAI adapter extracts content | Envelope normalization |
| Anthropic adapter extracts content | Envelope normalization |
| Mock adapter extracts content | Envelope normalization |
| Unknown provider raises | Adapter safety |
| Adapter missing field → handled | Graceful normalization failure |
| Similarity ≥ threshold → MATCH without judge | Similarity pre-filter |
| Similarity below threshold → falls through to judge | Pipeline routing |
| Similarity threshold boundary | 0.89 vs 0.91 |
| Judge verdict parsing (valid JSON) | Verdict handling |
| Judge verdict parsing (bad output → UNKNOWN) | Graceful failure |

### Integration tests

| Test | Validates |
|---|---|
| Primary responds before slow shadow completes | Decoupling / isolation |
| Shadow throws exception | Primary still returns 200 |
| Judge returns MISMATCH | Detailed log written |
| Primary failure | Shadow not triggered |
| Mixed providers (OpenAI primary, Anthropic shadow) | Adapter layer end-to-end |
| Client disconnect simulation | Shadow still completes |
| `GET /metrics` | Counters update correctly |

---

## 15. CI/CD (GitHub Actions)

```yaml
on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: "3.12"
      - run: pip install -r requirements.txt
      - run: pytest -v
      - run: ruff check .    # optional lint
```

---

## 16. README Outline

1. **Overview** — what shadow mode is and what this proxy does
2. **Architecture diagram** — sync primary path vs async shadow path
3. **Setup** — install, configure, run
4. **Config** — switching between mocks and real LLMs (provider + model + judge)
5. **Adapter layer** — how provider responses are reduced to a content string
6. **Example request** — curl against `POST /generate`
7. **How background task is decoupled** — `asyncio.create_task`, failure isolation
8. **Evaluation** — semantic similarity pre-filter → judge LLM fallback
9. **Mismatch logs** — location and format
10. **Running tests**
11. **Metrics endpoint**

---

## 17. Implementation Phases

| Phase | Scope | Done when |
|---|---|---|
| **1. Skeleton** | FastAPI app, health check, config loader | Server runs |
| **2. Mocks** | `/mock/primary`, `/mock/shadow` | Deterministic test responses |
| **3. Adapter layer** | Provider adapters → content string | Any provider → content string |
| **4. Sync proxy** | `POST /generate` → primary → adapter → return | Client gets response |
| **5. Shadow task** | Fire-and-forget shadow + adapter | Shadow content captured |
| **6. Similarity pre-filter** | Embeddings + cosine + threshold | Clear matches skip the judge |
| **7. Judge evaluation** | Send prompt + both contents → parse verdict | Inconclusive cases decided |
| **8. Mismatch logging** | Structured logs + metrics counters | Mismatches logged |
| **9. Integration tests** | Isolation + failure proofs | Engineering requirements met |
| **10. CI + README** | GitHub Actions, documentation | Submission-ready |
| **11. Metrics** | `GET /metrics` match rate | Stretch goal complete |

---

## 18. Future Enhancements

- Replace judge LLM with calibrated scoring model
- Cache embeddings / batch similarity to further cut judge calls
- Redis-backed metrics and job queue
- OpenTelemetry distributed tracing
- Grafana / Prometheus dashboard
- Multiple shadow models (A/B shadow testing)
- Plugin-style adapter registry (auto-discover providers)
- Replay system for failed / mismatched cases
- Persistent mismatch store (PostgreSQL / S3)

---

## 19. Acceptance Checklist

- [ ] `POST /generate` returns primary response without waiting on shadow
- [ ] Same prompt sent to both primary and shadow
- [ ] Adapter layer reduces each provider response to a content string
- [ ] Shadow runs asynchronously after response is sent
- [ ] Shadow failure does not affect primary status or latency
- [ ] Semantic similarity check runs first; matches above threshold skip the judge
- [ ] Judge LLM receives prompt + both content strings and returns a verdict for inconclusive cases
- [ ] Mismatches produce structured detailed logs
- [ ] Config file switches between mocks and real LLMs (provider + model + judge + threshold)
- [ ] Unit tests for adapters, similarity threshold, and judge verdict parsing pass
- [ ] Integration test proves primary stays fast under slow/failing shadow
- [ ] GitHub Actions pipeline runs on push
- [ ] README explains decoupling with architecture diagram
- [ ] `GET /metrics` shows real-time match percentage

---

## 20. Key Design Summary

| Layer | Role |
|---|---|
| **Primary path** | Synchronous, latency-critical, user-facing |
| **Shadow path** | Asynchronous, observability-driven, zero user impact |
| **Adapter layer** | Reduces each provider's response to a single content string |
| **Similarity pre-filter** | Cosine similarity on content strings; clear matches skip the judge |
| **Judge LLM** | Inconclusive cases: compares prompt + both contents → MATCH/MISMATCH |
| **Logging** | Lightweight for all; detailed for mismatches only |
| **Config** | Swappable primary/shadow/judge providers + models without code changes |
| **Evolution** | MVP in-process → queue-backed workers → full evaluator platform |
