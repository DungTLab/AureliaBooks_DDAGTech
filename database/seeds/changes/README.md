# Incremental Catalog Seeds

This directory contains append-only catalog data changes.

Rules:
- Create a new SQL file for each approved data change.
- Use unique, ordered filenames.
- Never modify a seed file after it has been deployed.
- Never hardcode AUTO_INCREMENT IDs.
- Never change database schema.
- Execute on Staging first.
- Require approval and backup before Production.
- Prevent duplicate execution with a deployment ledger.
- Validate foreign keys and existing data before execution.

No SQL files in this directory execute automatically.
