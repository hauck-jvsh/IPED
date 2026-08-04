# Local model verification — FR-065, decision D4

**Criterion**: the integration stays usable when driven by a local model, without depending on
capability only a frontier model has. Errors have to be self-correctable by the model itself
(Scenario 11).

**Why this one carries more weight than it looks.** Decision D3 leaves evidence content
unrestricted by default, which means the safeguard for confidentiality is operational rather than
technical: run against a local model and the content never leaves the workstation. If the
integration only works well with a hosted frontier model, that safeguard is theoretical and D3
becomes a much harder call to defend.

## Status: NOT YET MEASURED

Needs OpenCode configured against a local runtime and a real run of the thirty-question battery.

**FR-065 is unverified until the tables below are filled in.**

## Setup

| Item | Value |
|---|---|
| Harness | OpenCode |
| Runtime | (Ollama / LM Studio, version) |
| Model | (name, parameter count, quantization) |
| Hardware | (CPU / GPU, RAM) |
| Case | small reference case |

**Confirm the model is genuinely local before starting**: stop the runtime and ask a question. If an
answer still comes back, the harness is falling back to a hosted provider and this whole exercise
measures the wrong thing.

## Battery results

Run the thirty questions from [questions.md](questions.md) through the agent, in conversation, not
through the test harness. What is being measured here is the agent's behaviour, which
`InvestigationBatteryTest` deliberately does not cover.

| Metric | Target | Observed |
|---|---|---|
| Questions answered correctly | ≥ 90% (SC-001) | |
| False positives presented as a conclusion | 0 (SC-008) | |
| Conclusions carrying cited item ids | 100% (SC-009) | |
| Queries returning zero for a non-existent field name | < 5% (SC-006) | |

## Self-correction

The claim under test is that a smaller model recovers from its own mistakes because the error tells
it how. Record what actually happened.

| Error encountered | Did the model recover unaided? | What it did |
|---|---|---|
| `UNKNOWN_FIELD` with `details.similar` | | |
| `QUERY_SYNTAX` with a position | | |
| `CASE_NOT_OPEN` | | |
| `WRITE_NOT_ENABLED` | | |
| `INVALID_ARGUMENT` on a wrong type | | |

An error the model could not act on is a defect in that error's `remedy`, not a limitation of the
model. Note it here and fix the message.

## Behaviour observations

| Observation | Consequence |
|---|---|
| Did it orient with `iped_case_overview` before querying? | |
| Did it narrow with `iped_aggregate` rather than paging? | |
| Did it cite item ids in conclusions unprompted? | |
| Did it claim absence without checking the vocabulary? | |
| Did it try to reproduce content the workflow says not to? | |

## Verdict

- [ ] Usable with a local model without frontier-model capability
- [ ] Errors are self-correctable
- [ ] Evidence content confirmed not to leave the workstation

Anything unchecked is an open item, not a rounding error. D4 rests on all three.
