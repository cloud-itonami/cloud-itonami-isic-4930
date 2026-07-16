# Operator Guide

## First Deployment
1. Register the pipeline operator, segments and maintenance contractors;
   independently confirm each segment's operator-license/registration
   and each contractor's registration before seeding
   `pipelineops.store`.
2. Import existing throughput/billing, inspection-scheduling and
   maintenance-order history.
3. Run read-only throughput-record-logging and inspection-scheduling
   dry-runs (Phase 0-1).
4. Configure the rollout phase and the `coordinate-maintenance-order`
   cost-escalation threshold for human sign-off paths.
5. Publish a dry-run integrity-concern flag and audit export.

## Minimum Production Controls
- segment-registration/verification check before ANY proposal for that
  segment
- contractor-registration/verification check before ANY
  `:coordinate-maintenance-order` proposal
- governor gate on every proposal before commit
- human sign-off, immediately, for `:flag-integrity-concern` (always)
  and high-cost `:coordinate-maintenance-order` proposals
- audit export for every commit, hold and approval
- backup manual back-office process
- **do not extend this deployment with any op resembling pipeline
  operational control** (pressure/flow-rate/valve/shutoff adjustment) --
  that capability is permanently out of scope for this actor; a real
  SCADA/pipeline-control system is a wholly separate, independently
  certified system this actor never touches

## Certification
Certified operators must prove segment/contractor-verification
discipline, governor-bypass resistance, evidence-backed
integrity-concern reporting with immediate human review, and that no
deployed build has added any op resembling pipeline operational control.
