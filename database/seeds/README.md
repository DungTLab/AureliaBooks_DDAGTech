# AureliaBooks — Catalog Seed Data

## Purpose
Catalog seed files are maintained by the development team.

System schema and RBAC roles remain in docker/initdb/.
Demo users and demo fixtures are not production catalog seeds.

## Execution Order
1. 001_categories.sql
2. 002_publishers.sql
3. 003_authors.sql
4. 004_brands.sql
5. 005_products.sql
6. 006_books.sql
7. 007_book_authors.sql
8. 008_stationeries.sql

## Contribution Rules
- One SQL file per catalog table.
- Submit changes through Pull Requests.
- Do not hardcode AUTO_INCREMENT IDs.
- Do not use DROP, TRUNCATE, or DELETE.
- Do not insert real user accounts, passwords, or credentials.
- Use utf8mb4-compatible text.
- Resolve foreign keys using stable business identifiers.
- Preserve existing business data unless changes are explicitly approved.
- Seed products with stock_quantity = 0 initially.
- Do not seed inventory movements without inventory business logic.

## Unique Identifiers
- publishers: name (UNIQUE)
- brands: name (UNIQUE)
- products: barcode (UNIQUE)
- books: isbn (UNIQUE)
- book_authors: (book_id, author_id) composite primary key
- stationeries: product_id primary key

WARNING:
categories and authors do not have UNIQUE constraints on names.
Do not assume name-based UPSERT is safe.
An explicit identity and duplicate-prevention strategy is required
before executable seeds are approved.

## Deployment Policy
1. Review SQL in Pull Request.
2. Validate schema compatibility and dependencies.
3. Execute against Staging first.
4. Verify results and application behavior.
5. Back up Production before any Production execution.
6. Require manual Production approval.
7. Record seed execution versions and checksums.

These files must not be executed automatically on application startup.

After a seed version is released, preserve its execution history.
Future data changes must be tracked as new reviewed changes.
