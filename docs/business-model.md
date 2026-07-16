# Business Model: Pipeline-Transport Administrative/Logistics Coordination

## Classification
- Repository: `cloud-itonami-isic-4930`
- ISIC Rev.5: `4930` -- transport via pipeline
- Social impact: safety, environmental protection, transparency

## Customer
- independent pipeline-transport operators needing an auditable
  administrative/logistics-coordination platform
- multi-segment operators needing consistent inspection-scheduling/
  maintenance-order/integrity-concern governance across segments
- programs that cannot accept closed, unauditable back-office platforms
  for critical infrastructure

## Offer
- flow-volume/billing telemetry record logging
- pipeline-integrity-inspection / right-of-way maintenance-crew
  scheduling coordination
- maintenance-crew/equipment procurement coordination with registered,
  verified contractors
- integrity-concern flagging (leak detection, pressure anomaly,
  corrosion, integrity-inspection failure) for immediate human triage
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per segment/operator
- support retainer with SLA

## Trust Controls
- `:pipeline-transport-governor` never lets a proposal for an
  unregistered/unverified segment, or a maintenance order naming an
  unregistered/unverified contractor, commit or even escalate
- every proposal's `:effect` must be `:propose` -- a claim to directly
  actuate is a HARD, un-overridable block
- adjusting/overriding a pipeline operating parameter (pressure, flow
  rate, valve state), directly finalizing a
  pipeline-integrity-safety-clearance, and overriding/bypassing/
  disabling an emergency shutoff are ALL permanently out of scope --
  structurally absent from the closed op allowlist, not a rollout
  milestone
- an integrity-concern flag always requires immediate human sign-off, at
  every phase
- a high-cost maintenance-crew/equipment procurement order always
  requires human sign-off
- sensitive segment, operator-license and contractor data stays outside
  Git
