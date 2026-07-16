# cloud-itonami-isic-4930

Open Business Blueprint for **ISIC Rev.5 4930**: transport via pipeline
-- administrative/logistics coordination for a pipeline-transport
operator (oil, gas or chemical products moved via pipeline).

This repository publishes a pipeline-transport-administration
operations-COORDINATION actor -- flow-volume/billing telemetry record
logging, pipeline-integrity-inspection / right-of-way maintenance-crew
scheduling, maintenance-crew/equipment procurement coordination with
registered contractors, and integrity-concern flagging -- as an OSS
business that any qualified operator can fork, deploy, run, improve and
sell, so an independent pipeline operator never surrenders its
administrative/logistics data to a closed back-office SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, in-mem/Datomic checkpoints) -- the same actor pattern as
every prior actor in this fleet -- here it is **PipelineTransportAdvisor
⊣ PipelineTransportGovernor**. This blueprint's own
`:itonami.blueprint/governor` keyword, `:pipeline-transport-governor`,
is grep/search-verified unique fleet-wide -- a distinct, independent
build, and deliberately distinct from sibling ISIC 4950's own
`:pipeline-integrity-governor` (a different, more specific "Transport
via pipeline (petroleum)" class covering intake-through-delivery batch
custody, whose own actor still gates real dispatch/settlement acts
behind mandatory human sign-off at every phase).

## HIGH-HAZARD CRITICAL INFRASTRUCTURE -- coordination/monitoring only, never operational control

Pipeline transport (oil, gas, chemical products via pipeline) is
critical infrastructure with catastrophic-failure-mode risk (explosion,
toxic release, environmental contamination). **This actor is
coordination/monitoring-only. It never has, and structurally cannot be
given, any op that could be construed as controlling pipeline valves,
pressure, flow rate, or shutoff systems -- not even as a "proposal."**
The closed op allowlist below contains four purely administrative/
logistics ops (scheduling inspections, logging flow/volume records for
billing, coordinating maintenance crews, flagging a concern for a
human) and nothing else. There is no op in this actor, gated or
otherwise, that resembles adjusting a pipeline operating parameter.

> **Why an actor layer at all?** An LLM is great at drafting a
> throughput-record summary, an inspection-scheduling proposal, or a
> maintenance-order request -- but it has no license to adjust a real
> pipeline's operating parameters, no way to independently confirm a
> pipeline segment or a maintenance contractor is actually a
> registered/verified counterparty, and no notion of when a "flag this
> concern" op quietly turns into a claim to have already acted on it.
> Letting it act directly invites an unverified segment's data entering
> the ledger, an unverified contractor receiving a maintenance order, or
> -- worst of all -- a fabricated claim to have adjusted pipeline
> pressure or overridden an emergency shutoff, exposing the crew, the
> public and the environment to real physical risk. This project seals
> the PipelineTransportAdvisor into a single node and wraps it with an
> independent **PipelineTransportGovernor**, a human **approval
> workflow**, and an immutable **audit ledger**.

## Scope: administrative coordination only, never operational control

This actor is **administrative/logistics coordination only**. It never
performs, authorizes, or even proposes:

- adjusting or overriding a pipeline operating parameter (pressure,
  flow rate, valve state)
- directly finalizing a pipeline-integrity-safety-clearance
- overriding, bypassing or disabling an emergency shutoff system
- any other form of operational control of the pipeline

The governor's `scope-exclusion-violations` check re-scans every
proposal for this failure mode independently of the advisor's own
framing, and treats it as a HARD, permanent block regardless of
confidence or how clean everything else is. Flagging an integrity
concern for a human to triage immediately is exactly this actor's job --
`:flag-integrity-concern` is never excluded by this check, only
adjusting/overriding/finalizing that concern's underlying operating
parameter is. `:flag-integrity-concern` also ALWAYS escalates
immediately to a human and is never in any phase's `:auto` set --
safety-critical, not merely a business-process convenience.

### Actuation

**Every proposal this actor generates is `:effect :propose`, never a
direct actuation.** Two independent layers enforce this
(`pipelineops.governor`'s `effect-not-propose-violations` HARD check
and `pipelineops.phase`'s phase table, which never puts
`:flag-integrity-concern` in any phase's `:auto` set). A human
pipeline-administration coordinator is always the one who actually acts
on a flagged concern or confirms a high-cost maintenance order. This
actor structurally has nothing it could ever be authorized to actuate
directly -- unlike some sibling actors, there is no legitimate future
`:effect` other than `:propose` for this vertical, ever.

## The core contract

```
segment/contractor registration + administrative-coordination request
        |
        v
   ┌───────────────────────┐   proposal      ┌────────────────────────────┐
   │ PipelineTransport-    │ ─────────────▶ │ PipelineTransportGovernor   │  (independent system)
   │ Advisor (sealed)      │  + citations    │ segment-unverified ·        │
   └───────────────────────┘                 │ contractor-unverified (NEW)·│
          │                 commit ◀┼ effect-not-propose ·               │
          │                         │ scope-excluded (pipeline-          │
    record + ledger        escalate ┼ operational-control content) ·      │
          │              (ALWAYS for│ op-not-allowed                      │
          │       :flag-integrity-  │                                      │
          │       concern/high-cost └────────────────────────────┘
          │       maintenance order)
          ▼
      human approval
```

**The PipelineTransportAdvisor never commits a proposal the
PipelineTransportGovernor would reject, and an integrity-concern flag or
a high-cost maintenance order never commits without a human sign-off.**
Hard violations (an unregistered/unverified segment; an unregistered/
unverified maintenance contractor; a non-`:propose` effect; content
touching pipeline-operational-control; an op outside the closed
allowlist) force **hold** and *cannot* be approved past.

## Features

- **Closed proposal-op allowlist, administrative-only**:
  `log-throughput-record`, `schedule-inspection-operation`,
  `coordinate-maintenance-order`, `flag-integrity-concern` (all `:effect
  :propose`). No op resembling pipeline operational control exists in
  this allowlist at all.
- **Four HARD governor checks** (permanent, un-overridable):
  1. **Segment unverified** -- the target pipeline segment's
     operator-license/registration must exist AND be independently
     registered/verified in the store.
  2. **Contractor unverified** (FLAGSHIP NEW) -- for
     `:coordinate-maintenance-order` only, the named contractor must
     exist AND be independently registered/verified -- a maintenance
     counterparty-verification gate.
  3. **Effect is :propose** -- any other `:effect` value is rejected.
  4. **Scope exclusion** -- directly finalizing a pipeline-integrity-
     safety-clearance, adjusting/overriding a pressure or flow-rate
     parameter, overriding/bypassing/disabling an emergency shutoff, and
     an op outside the closed allowlist are all permanently blocked.
- **Two ESCALATE (SOFT) gates**, either forces human sign-off:
  - `:flag-integrity-concern` -- ALWAYS escalates immediately,
    regardless of confidence or phase. Safety-critical: a leak-
    detection/pressure-anomaly/corrosion/integrity-inspection-failure
    concern is never auto-commit eligible and never finalizes anything
    itself -- it only surfaces the concern for a human without delay.
  - `:coordinate-maintenance-order` above a cost threshold -- a
    large-value procurement proposal always needs a human sign-off.
  - (LLM confidence below the floor also escalates, as with every
    sibling actor.)
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: throughput-record logging only (approval-gated)
  - Phase 2: + inspection-operation scheduling, maintenance-order
    proposals (approval-gated)
  - Phase 3: auto-commits clean, high-confidence, low-cost proposals
    (integrity concerns and high-cost maintenance orders always
    escalate)
- **Append-only audit ledger** -- every decision is an immutable log
  entry.
- **langgraph-clj StateGraph** -- one request = one supervised run;
  human-in-the-loop via `interrupt-before`.

### Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

### Test suite

- `test/pipelineops/governor_test.clj` -- unit tests of governor hard
  checks, scope exclusion, and the self-trip regression test
- `test/pipelineops/advisor_test.clj` -- advisor proposal shape and
  consistency
- `test/pipelineops/phase_test.clj` -- rollout phase logic
- `test/pipelineops/governor_contract_test.clj` -- full graph
  integration, audit trail
- `test/pipelineops/store_contract_test.clj` -- Store protocol and
  MemStore implementation

### Modules

- `pipelineops.store` -- SSoT (MemStore, String-keyed segment/contractor
  directories, append-only ledger)
- `pipelineops.advisor` -- contained intelligence node (mock +
  real-LLM seam)
- `pipelineops.governor` -- independent compliance layer
- `pipelineops.phase` -- staged rollout (0→3)
- `pipelineops.operation` -- langgraph-clj StateGraph
- `pipelineops.sim` -- demo driver

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`4930`).

## Business-process coverage (honest)

| Covered | Not covered (out of scope, permanently -- not a gap) |
|---|---|
| Flow-volume/billing telemetry record logging (`:log-throughput-record`) | Real SCADA/telemetry-system integration |
| Pipeline-integrity-inspection / right-of-way maintenance-crew scheduling coordination (`:schedule-inspection-operation`) | Direct crew time-clock/payroll integration |
| Maintenance-crew/equipment procurement coordination with a registered, verified contractor, HARD-gated on contractor verification (`:coordinate-maintenance-order`) | Real procurement/ERP-system integration |
| Integrity-concern flagging, ALWAYS human-gated immediately (`:flag-integrity-concern`) | Any adjustment of pipeline operating parameters (pressure, flow rate, valve state) -- permanently out of scope, structurally absent from the allowlist |
| Immutable audit ledger for every log/schedule/order/flag decision | Directly finalizing a pipeline-integrity-safety-clearance or an emergency-shutoff override -- permanently out of scope, not a gap |

Extending coverage is additive: add the next purely administrative op
(e.g. a right-of-way-encroachment-report or a
regulatory-filing-preparation check) as its own governed op with its own
HARD checks and tests, following the SAME "an independent governor
re-verifies against the actor's own records before any real-world act"
pattern this repo's flagship checks already establish -- and following
the SAME "never add an op resembling operational control" boundary this
repo establishes as permanent, not provisional.

## Maturity

`:implemented` -- `PipelineTransportAdvisor` + `PipelineTransportGovernor`
run as real, tested code (see `Development` above), following the SAME
governed-actor architecture as every prior actor across this fleet, with
its own distinct, independently-named governor and its own novel
maintenance-contractor-verification check.

## License

Code and implementation templates are AGPL-3.0-or-later.
