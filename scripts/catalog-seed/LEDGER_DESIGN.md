# AureliaBooks — External Seed Ledger Design

## Constraints
- Database schema is frozen.
- No new MySQL tables or columns.
- No changes to JPA entities.
- Catalog seeds run independently of application deployment.

## Environments
- Staging: /opt/apps/swp391-staging/seed-ledger/
- Production: /opt/apps/swp391/seed-ledger/

## Ledger Fields
- seed_file
- sha256
- git_commit
- environment
- status
- started_at
- completed_at

## Execution Rules
1. Only execute reviewed SQL from an approved Git commit.
2. Skip comment-only templates; never mark them APPLIED.
3. Validate file identity, syntax and dependencies.
4. Acquire an exclusive execution lock.
5. Persist execution intent before submitting SQL.
6. Verify execution results before marking APPLIED.
7. Never automatically retry an uncertain execution.
8. Refuse modified checksums for previously applied versions.
9. Keep incremental seed files immutable after release.
10. Back up and restore the ledger together with MySQL.

## Failure Handling
- Interrupted execution: stop and require reconciliation.
- Unknown database state: mark NEEDS_REVIEW.
- Checksum conflict: block execution.
- Missing dependency: block execution.
- Ledger mismatch after restore: block execution.
- Never interpret an unexpected zero-row INSERT as success.

## Production Safety
- Staging execution and validation required first.
- Production backup and manual approval required.
- No automatic Production execution on application deploy.

## Current Implementation
Dry Run v1 only.
Ledger persistence and SQL execution are NOT implemented.
