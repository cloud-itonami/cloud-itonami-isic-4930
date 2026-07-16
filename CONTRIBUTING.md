# Contributing

`cloud-itonami-isic-4930` accepts contributions to the OSS blueprint,
capability bindings, policy tests, documentation and operator model.

## Development

```bash
clojure -M:test
clojure -M:lint
```

## Rules
- Do not commit real pipeline-segment, operator-license, contractor or
  integrity-incident data.
- Keep throughput-record logging, inspection-operation scheduling,
  maintenance-order coordination and integrity-concern flagging behind
  the PipelineTransportGovernor.
- **NEVER add an op that could be construed as controlling pipeline
  valves, pressure, flow rate, or shutoff systems -- not even as a
  gated "proposal."** This is a permanent, structural boundary of this
  actor, not a rollout milestone. If a contribution needs anything that
  even loosely reads as "adjust pipeline operating parameters," it does
  not belong in this repository.
- Treat pipeline-transport-administration workflows as high-risk critical-
  infrastructure adjacent: add tests for segment/contractor
  verification, effect discipline, scope exclusion, escalation and
  audit logging.
- Never phrase a governor scope-exclusion term as a bare noun (e.g.
  "pressure", "shutoff", "valve") -- phrase it as the
  finalization/execution ACTION (e.g. "adjusted the pipeline pressure",
  "overrode the emergency shutoff"), and add/extend the
  `default-mock-advisor-proposals-never-self-trip-scope-exclusion`
  regression test for any new term. A bare-noun term will self-trip this
  actor's own legitimate `:flag-integrity-concern` happy path -- see
  `pipelineops.governor/scope-excluded-terms`'s docstring.
- `:flag-integrity-concern` must always escalate immediately and must
  never be added to any phase's `:auto` set -- safety-critical, not
  merely a business-process convenience.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
