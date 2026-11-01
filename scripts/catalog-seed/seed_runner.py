#!/usr/bin/env python3
"""AureliaBooks Catalog Seed Runner — Read-only v2."""

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

from sql_policy import check_sql

ROOT = Path(__file__).resolve().parents[2]
SEED_DIR = ROOT / "database" / "seeds"

SEED_ORDER = (
    "001_categories.sql",
    "002_publishers.sql",
    "003_authors.sql",
    "004_brands.sql",
    "005_products.sql",
    "006_books.sql",
    "007_book_authors.sql",
    "008_stationeries.sql",
)

VALID_STATUSES = {
    "PENDING",
    "EXECUTING",
    "APPLIED",
    "NEEDS_REVIEW",
}


def load_ledger(path, environment):
    if path.is_symlink() or not path.is_file():
        raise ValueError("Ledger file missing or invalid")

    data = json.loads(path.read_text(encoding="utf-8"))

    if not isinstance(data, dict):
        raise ValueError("Ledger must be a JSON object")

    if data.get("version") != 1:
        raise ValueError("Unsupported ledger version")

    if data.get("environment") != environment:
        raise ValueError("Ledger environment mismatch")

    rows = data.get("entries")

    if not isinstance(rows, list):
        raise ValueError("Ledger entries must be an array")

    entries = {}

    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("Invalid ledger entry")

        required = {
            "seed_file",
            "sha256",
            "git_commit",
            "environment",
            "status",
            "started_at",
            "completed_at",
        }

        if not required.issubset(row):
            raise ValueError("Incomplete ledger entry")

        name = row["seed_file"]
        checksum = row["sha256"]
        status = row["status"]

        if not isinstance(name, str) or name not in SEED_ORDER:
            raise ValueError("Unknown seed file in ledger")

        if name in entries:
            raise ValueError("Duplicate ledger entry")

        if (
            not isinstance(checksum, str)
            or not re.fullmatch(r"[a-f0-9]{64}", checksum)
        ):
            raise ValueError("Invalid SHA-256 in ledger")

        if not isinstance(status, str) or status not in VALID_STATUSES:
            raise ValueError("Unknown ledger status")

        if row["environment"] != environment:
            raise ValueError("Entry environment mismatch")

        entries[name] = row

    return entries


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true", required=True)
    parser.add_argument(
        "--environment",
        choices=("staging", "production"),
        required=True,
    )
    parser.add_argument("--ledger", type=Path, required=True)
    args = parser.parse_args()

    try:
        ledger = load_ledger(args.ledger, args.environment)
    except (ValueError, OSError, UnicodeError) as exc:
        sys.exit(f"BLOCKED: {exc}")

    try:
        actual = sorted(p.name for p in SEED_DIR.glob("*.sql"))

        if actual != sorted(SEED_ORDER):
            sys.exit("BLOCKED: Seed file inventory mismatch")

        print("=== AureliaBooks Seed Runner v2 ===")
        print(f"Environment: {args.environment}")
        print("Mode: READ-ONLY")
        print("Database connection: DISABLED\n")

        blocked = False

        for filename in SEED_ORDER:
            path = SEED_DIR / filename

            if path.is_symlink() or not path.is_file():
                sys.exit(f"BLOCKED: Invalid file {filename}")

            content = path.read_bytes()

            if not content:
                sys.exit(f"BLOCKED: Empty file {filename}")

            sql = content.decode("utf-8")
            checksum = hashlib.sha256(content).hexdigest()

            try:
                sql_status = check_sql(filename, sql)
            except ValueError as exc:
                print(f"{filename}")
                print(f"  SHA256: {checksum}")
                print(f"  Status: BLOCKED: SQL_POLICY_VIOLATION ({exc})")
                blocked = True
                continue

            has_content = sql_status != "TEMPLATE_ONLY"

            entry = ledger.get(filename)

            if entry:
                if entry["sha256"] != checksum:
                    status = "BLOCKED: CHECKSUM_MISMATCH"
                    blocked = True
                elif entry["status"] != "APPLIED":
                    status = "BLOCKED: REQUIRES_REVIEW"
                    blocked = True
                elif not has_content:
                    status = "BLOCKED: TEMPLATE_MARKED_APPLIED"
                    blocked = True
                else:
                    status = "APPLIED"
            elif has_content:
                status = "NOT_APPLIED"
            else:
                status = "TEMPLATE_ONLY"

            print(f"{filename}")
            print(f"  SHA256: {checksum}")
            print(f"  Status: {status}")

        print("\nNO SQL EXECUTED")
        print("NO DATABASE CONNECTION")
        print("NOTE: SQL syntax and dependencies not validated")

        if blocked:
            sys.exit("BLOCKED: Ledger reconciliation required")

        print("PASS: Read-only ledger planning completed")

    except (OSError, UnicodeError) as exc:
        sys.exit(f"BLOCKED: Cannot read seed files: {exc}")


if __name__ == "__main__":
    main()
