# Security Policy

This project handles pipeline-transport administrative/logistics
coordination and integrity-concern workflows for high-hazard critical
infrastructure. Treat vulnerabilities as potentially high impact even
when the demo data is synthetic -- oil/gas/chemical pipeline transport
carries catastrophic-failure-mode risk (explosion, toxic release,
environmental contamination).

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- real pipeline-segment, operator-license or contractor data exposure
- authorization bypass
- PipelineTransportGovernor bypass, especially anything that would let a
  proposal resembling operational control (pressure/flow/valve/shutoff
  adjustment) reach the closed op allowlist or bypass scope exclusion
- audit-ledger tampering
- over-disclosure in integrity-concern reports or exports
- tenant isolation failures

## Reporting

Use GitHub private vulnerability reporting when available for the repository.
If that is unavailable, contact the repository maintainers through the
cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on segment/contractor data, policy enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real pipeline-segment, operator-license and contractor data
  outside this repository.
- Run policy tests before deployment, especially the scope-exclusion
  regression suite.
- Export and review audit logs regularly.
- Use least privilege for operators and service accounts.
- Never deploy a build of this actor that has added any op resembling
  pipeline operational control -- that is out of scope for this
  repository, permanently.
