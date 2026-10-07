# Document-based inventory baseline

The initialization schema has 21 base tables and the read-only `inventory_movements`
view. Warehouse history is derived from posted receipt/order documents. Security
audit data remains in `audit_logs`.

## Fresh development database

Docker runs `initdb/01_schema.sql` and `02_seed_roles.sql` only when its data directory
is empty. The role seed has no opening stock. Record opening quantities through a
posted goods receipt; directly seeding `products.stock_quantity` will prevent full
period reconciliation.

## Existing populated development database

Restarting Docker does not migrate an existing volume. Do not run initialization
SQL against it: that script drops/recreates the database. Do not delete the volume
to resolve `ddl-auto: validate` failures.

1. Export a database backup and verify it can be restored in isolated storage.
2. Compare the existing DDL with `initdb/01_schema.sql`. Add receipt posting actor
   and order deduction/restoration metadata in a controlled migration.
3. Backfill only from verified source documents and historical posting evidence.
   Current order/payment status or `updated_at` is insufficient evidence that
   stock was deducted/restored. A payment refund is not a physical return.
4. Reconcile receipt/order quantities, document posting timestamps/actors and
   current product balances. Enforce the new posting constraints only after
   existing records satisfy them. Keep posted document lines immutable.
5. Archive any retired inventory log data outside the active schema before
   removing its table. Past manual damage/loss adjustments cannot be reconstructed
   from receipt/order lines; do not silently discard them or invent receipts.
6. Create the `inventory_movements` view from the authoritative schema, then verify
   the active baseline is exactly 21 tables plus the view and run JPA validation.

No automatic migration/backfill or live data deletion is performed by this change.
Legacy history coverage depends on the evidence present in that database; unresolved
historical adjustments need an explicit migration decision before period reporting.

## Sprint implementation contract

`StockMutationService`, `InventoryHistoryService` and `InventoryMovementRepository`
are scaffolds. Receipt posting, checkout deduction and cancellation/successful full
return restoration must update stock and source-document markers atomically and
idempotently. The view does not execute those operations. A staff member's successful
return confirmation restores stock immediately, without a manual adjustment step.
