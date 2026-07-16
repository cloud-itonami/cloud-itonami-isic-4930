# Governance

`cloud-itonami-isic-4930` is an OSS open-business blueprint for
pipeline-transport administrative/logistics operations coordination
(ISIC Rev.5 4930 -- transport via pipeline).

## Maintainers
Maintainers may merge changes that preserve these invariants:
- this actor is coordination/monitoring-only and MUST NEVER gain an op
  that could be construed as controlling pipeline valves, pressure,
  flow rate, or shutoff systems -- not even as a gated "proposal." The
  closed op-allowlist is a hard boundary, not a rollout stage.
- a proposal for an unverified/unregistered pipeline segment, or a
  maintenance order naming an unverified/unregistered contractor, can
  never commit.
- the PipelineTransportGovernor remains independent of the advisor.
- hard policy violations (non-`:propose` effect, content touching
  pipeline-operational-control, an op outside the closed allowlist)
  cannot be overridden by human approval.
- `:flag-integrity-concern` always escalates immediately to a human and
  is never added to any phase's `:auto` set.
- every throughput-record log, inspection-operation schedule,
  maintenance-order coordination and integrity-concern flag is
  auditable.
- pipeline-segment, operator-license, and contractor data stays outside
  Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or
license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification
is a separate trust mark and should require security, audit and
data-flow review.

Certified operators can lose certification for:
- bypassing throughput-record, inspection-scheduling, maintenance-order
  or integrity-concern policy checks
- mishandling pipeline-segment, operator-license or contractor data
- misrepresenting certification status
- failing to respond to integrity/safety incidents immediately
- attempting to extend this actor with any op resembling pipeline
  operational control
