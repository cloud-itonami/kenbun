# Security Policy

## Reporting a vulnerability

Use **GitHub private vulnerability reporting** on this repository — the
*Security* tab, *Report a vulnerability*. It is enabled, so the button is
really there; if it ever is not, that is itself worth reporting.

Do not open a public issue for a suspected vulnerability, credential leak or
privacy incident.

Include the affected revision, reproduction steps, and observed impact. **Do
not include real credentials, tokens, keys or personal data** in a report — a
path and a description are enough, and a report is not a safe place to put the
thing you are reporting about.

## What in this repository is security-relevant

kenbun (検分) decides whether a submitted bug report is a defect report or only
an assertion. **Submissions routinely describe unfixed vulnerabilities**, so the
sensitive property is not the runtime but the data flowing through it: who can
read a pending submission, what is retained, and what leaves in an export.

Out of scope: the defects described *inside* submissions — report those to the
project they concern, not here.

## What is not claimed

This repository carries **no third-party security certification**. There is no
SOC 2 report, no ISO/IEC 27001 certificate and no ISMAP registration covering
it, and none is implied by whatever checks run here.

The workspace-level assurance position — which controls have design evidence,
which have implementation evidence, and which have no operating evidence at all
— is recorded in [`kotoba-lang/security`](https://github.com/kotoba-lang/security).
Read the current figures there with

```sh
kbb --backend sci --classpath src scripts/check-crosswalk.cljs
```

rather than quoting a number from this file, which would be stale the moment it
was written.
