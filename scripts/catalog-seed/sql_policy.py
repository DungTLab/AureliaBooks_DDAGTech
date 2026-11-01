#!/usr/bin/env python3
"""AureliaBooks static SQL policy — no database execution."""

import re
from pathlib import Path

TABLES = {
    "001_categories.sql": "categories",
    "002_publishers.sql": "publishers",
    "003_authors.sql": "authors",
    "004_brands.sql": "brands",
    "005_products.sql": "products",
    "006_books.sql": "books",
    "007_book_authors.sql": "book_authors",
    "008_stationeries.sql": "stationeries",
}

FORBIDDEN = re.compile(
    r"\b(?:ALTER|CREATE|DROP|TRUNCATE|DELETE|UPDATE|REPLACE|"
    r"GRANT|REVOKE|CALL|EXECUTE|PREPARE|DEALLOCATE|LOAD|"
    r"OUTFILE|DUMPFILE|SLEEP|BENCHMARK|SELECT|LOAD_FILE)\b",
    re.I,
)

INSERT_START = re.compile(
    r"^INSERT\s+INTO\s+([a-z_][a-z0-9_]*)\b",
    re.I,
)


def mask_strings_comments(text):
    out = list(text)
    i, n = 0, len(text)

    while i < n:
        start = i

        if text[i] == "#" or (
            text.startswith("--", i)
            and (i + 2 == n or text[i + 2].isspace())
        ):
            end = text.find("\n", i)
            i = n if end == -1 else end

        elif text.startswith("/*", i):
            if text.startswith(("/*!", "/*+"), i):
                raise ValueError(
                    "Executable/hint SQL comment is not allowed"
                )

            end = text.find("*/", i + 2)
            if end < 0:
                raise ValueError("Unclosed block comment")
            i = end + 2

        elif text[i] in ("'", '"', "`"):
            quote = text[i]
            i += 1
            closed = False

            while i < n:
                if text[i] == "\\":
                    i += 2
                elif text[i] == quote:
                    if i + 1 < n and text[i + 1] == quote:
                        i += 2
                    else:
                        i += 1
                        closed = True
                        break
                else:
                    i += 1

            if not closed:
                raise ValueError(
                    "Unclosed quoted string or identifier"
                )

        else:
            i += 1
            continue

        for k in range(start, min(i, n)):
            if out[k] != "\n":
                out[k] = " "

    return "".join(out)


def check_sql(filename, sql):
    code = mask_strings_comments(sql)

    if not code.strip():
        return "TEMPLATE_ONLY"

    if not code.rstrip().endswith(";"):
        raise ValueError("Missing final semicolon")

    count = 0

    for statement in code.split(";"):
        statement = statement.strip()
        if not statement:
            continue

        match = INSERT_START.match(statement)

        if not match:
            raise ValueError(
                "Only INSERT INTO statements are permitted"
            )

        if match.group(1).lower() != TABLES[filename]:
            raise ValueError(
                "INSERT targets the wrong catalog table"
            )

        if FORBIDDEN.search(statement):
            raise ValueError("Disallowed SQL keyword")

        count += 1

    return f"INSERT_ONLY ({count} statements)"


def main():
    root = Path(__file__).resolve().parents[2]
    seeds = root / "database/seeds"

    for filename in TABLES:
        path = seeds / filename

        if path.is_symlink() or not path.is_file():
            raise SystemExit(
                f"BLOCKED: Missing/invalid {filename}"
            )

        try:
            status = check_sql(
                filename,
                path.read_text(encoding="utf-8"),
            )
        except (ValueError, OSError, UnicodeError) as exc:
            raise SystemExit(
                f"BLOCKED: {filename}: {exc}"
            )

        print(f"{filename}: {status}")

    print(
        "PASS: Static policy inspection complete; "
        "NO SQL EXECUTED"
    )


if __name__ == "__main__":
    main()
