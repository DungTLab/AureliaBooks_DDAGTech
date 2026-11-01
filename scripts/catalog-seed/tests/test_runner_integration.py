"""Integration tests for the read-only Catalog Seed Runner."""

import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class TestRunnerIntegration(unittest.TestCase):

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

        repo = Path(__file__).resolve().parents[3]
        tools = Path(__file__).resolve().parents[1]

        self.scripts = self.root / "scripts/catalog-seed"
        self.scripts.mkdir(parents=True)

        for name in ("seed_runner.py", "sql_policy.py"):
            shutil.copy2(tools / name, self.scripts / name)

        seed_source = repo / "database/seeds"
        seed_dest = self.root / "database/seeds"
        seed_dest.parent.mkdir(parents=True)

        shutil.copytree(seed_source, seed_dest)

        self.seed = seed_dest / "001_categories.sql"
        self.runner = self.scripts / "seed_runner.py"
        self.ledger = self.root / "ledger.json"

        self.data = {
            "version": 1,
            "environment": "staging",
            "entries": [],
        }

        self.save_ledger()

    def tearDown(self):
        self.temp.cleanup()

    def save_ledger(self):
        self.ledger.write_text(
            json.dumps(self.data),
            encoding="utf-8",
        )

    def run_runner(self):
        return subprocess.run(
            [
                sys.executable,
                str(self.runner),
                "--dry-run",
                "--environment",
                "staging",
                "--ledger",
                str(self.ledger),
            ],
            capture_output=True,
            text=True,
            check=False,
        )

    def test_comment_only_templates(self):
        result = self.run_runner()

        self.assertEqual(result.returncode, 0)
        self.assertEqual(
            result.stdout.count("Status: TEMPLATE_ONLY"),
            8,
        )
        self.assertIn("NO SQL EXECUTED", result.stdout)

    def test_valid_insert_is_not_applied(self):
        self.seed.write_text(
            "INSERT INTO categories (name) VALUES ('Books');",
            encoding="utf-8",
        )

        result = self.run_runner()

        self.assertEqual(result.returncode, 0)
        self.assertIn("Status: NOT_APPLIED", result.stdout)
        self.assertIn("NO DATABASE CONNECTION", result.stdout)

    def test_unsafe_sql_is_blocked(self):
        cases = {
            "ALTER": (
                "ALTER TABLE categories ADD COLUMN code INT;"
            ),
            "INSERT_SELECT": (
                "INSERT INTO categories (name) "
                "SELECT email FROM users;"
            ),
            "LOAD_FILE": (
                "INSERT INTO categories (name) "
                "VALUES (LOAD_FILE('/etc/passwd'));"
            ),
        }

        for name, sql in cases.items():
            with self.subTest(case=name):
                self.seed.write_text(sql, encoding="utf-8")

                result = self.run_runner()

                self.assertNotEqual(result.returncode, 0)
                self.assertIn(
                    "SQL_POLICY_VIOLATION",
                    result.stdout,
                )

    def test_checksum_mismatch_is_blocked(self):
        self.seed.write_text(
            "INSERT INTO categories (name) VALUES ('Books');",
            encoding="utf-8",
        )

        self.data["entries"] = [{
            "seed_file": "001_categories.sql",
            "sha256": "0" * 64,
            "git_commit": "f" * 40,
            "environment": "staging",
            "status": "APPLIED",
            "started_at": None,
            "completed_at": None,
        }]

        self.save_ledger()
        result = self.run_runner()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("CHECKSUM_MISMATCH", result.stdout)

    def test_template_cannot_be_marked_applied(self):
        self.data["entries"] = [{
            "seed_file": "001_categories.sql",
            "sha256": hashlib.sha256(
                self.seed.read_bytes()
            ).hexdigest(),
            "git_commit": "f" * 40,
            "environment": "staging",
            "status": "APPLIED",
            "started_at": None,
            "completed_at": None,
        }]

        self.save_ledger()
        result = self.run_runner()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(
            "TEMPLATE_MARKED_APPLIED",
            result.stdout,
        )


if __name__ == "__main__":
    unittest.main()
