# AureliaBooks development accounts and first production admin

## Local / coursework demo

Run `docker compose up -d mysql-db`. Compose automatically loads
`docker-compose.override.yml`, including `docker/demo/03_seed_users.sql` on a
**fresh** MySQL data volume. Existing volumes do not rerun init scripts; restarting
Spring Boot or MySQL does not reset accounts. Do not remove your volume to refresh users.

| Role | Demo email | Demo password |
|---|---|---|
| ADMIN | aureliabook.team@gmail.com | DDAGTechAdmin391@ |
| MANAGER | manager@aureliabook.vn | Manager@123 |
| SALE_STAFF | staff@aureliabook.vn | Staff@123 |
| CUSTOMER | customer@aureliabook.vn | Customer@123 |
| MANAGER (supplementary) | kho.nguyen@aureliabook.vn | Manager@123 |
| SALE_STAFF (supplementary) | lan.tran@aureliabook.vn | Staff@123 |
| CUSTOMER (supplementary) | anhntdce200031@gmail.com | Customer@123 |

These credentials are public **demo fixtures**, never production credentials.
The SQL stores BCrypt cost-12 hashes, resolves role IDs by role name, and never
overwrites an existing password, role, name or active flag. Email/phone ownership
conflicts abort and roll back the whole seed. It does not claim contacts are verified.
The supplementary fixtures from main use the same mechanism. The legacy
`docker/initdb/03_seed_users.sql` is removed; it must not be run alongside this seed.
The agreed Admin is `aureliabook.team@gmail.com`, not the legacy Admin email.

For an existing development DB, run the roles script and demo users script in a
MySQL console after reviewing account conflicts. Do not rerun `01_schema.sql`:
that schema script recreates the database. The previous opt-in
`03_seed_test_accounts.sql` is replaced by this single demo-account mechanism.

## Production

Supply `MYSQL_ROOT_PASSWORD`, `DB_USER`, and `DB_PASS` through the deployment
environment or an untracked `.env`, then use this **explicit** file list:

```shell
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d mysql-db
```

This excludes the automatically discovered local override, sample catalog and
demo accounts. A fresh DB receives the schema and four foundational roles only.
For an existing production DB, use the existing database/migration process;
never rerun the destructive schema initializer.

Set the following variables on the **Spring Boot process/container** to create
the first administrator (variables in the MySQL container alone do not configure Java):

```text
BOOTSTRAP_ADMIN_ENABLED=true
BOOTSTRAP_ADMIN_EMAIL=<business administrator email>
BOOTSTRAP_ADMIN_PASSWORD=<secret satisfying the password policy>
BOOTSTRAP_ADMIN_PHONE=<optional 10-digit phone>
```

Configure the normal `DB_HOST`, `DB_PORT`, `DB_NAME=aurelia_books_db`, `DB_USER`
and `DB_PASS` for Spring Boot. Google/SMTP credentials are separate configuration.
The bootstrap runs in one transaction, locks the ADMIN role, hashes the secret,
and writes an `ADMIN_BOOTSTRAPPED` audit event. It creates an admin only when
none exists, including inactive administrators in that check. It never promotes
another account, resets a password, or reactivates an administrator. Concurrent
startup requests serialize on the same role lock.

After the first successful deployment, disable `BOOTSTRAP_ADMIN_ENABLED` and
remove the bootstrap password from the runtime configuration. Restarting keeps
the existing account. Never commit a real `.env` or reuse the demo password in production.

## Opt-in bootstrap persistence test

`AdminBootstrapMySqlTest` requires `-Dbootstrap.mysql=true` and a **fresh** database
whose name starts with `auth_bootstrap_qa_`, supplied through the DB environment
variables. It mutates only that disposable QA database. Regular tests leave this
integration check disabled.
