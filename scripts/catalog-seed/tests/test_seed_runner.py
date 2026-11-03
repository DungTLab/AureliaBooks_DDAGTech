"""Tests for AureliaBooks read-only seed ledger."""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(
    0,
    str(Path(__file__).resolve().parents[1])
)

from seed_runner import load_ledger


class TestSeedLedger(unittest.TestCase):

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "ledger.json"

        self.entry = {
            "seed_file": "001_categories.sql",
            "sha256": "a" * 64,
            "git_commit": "f" * 40,
            "environment": "staging",
            "status": "APPLIED",
            "started_at": None,
            "completed_at": None,
        }

        self.valid = {
            "version": 1,
            "environment": "staging",
            "entries": [],
        }

    def tearDown(self):
        self.temp.cleanup()

    def write(self, data):
        self.path.write_text(
            json.dumps(data),
            encoding="utf-8",
        )

    def test_empty_ledger(self):
        self.write(self.valid)
        self.assertEqual(
            load_ledger(self.path, "staging"),
            {},
        )

    def test_valid_applied_entry(self):
        data = {
            **self.valid,
            "entries": [self.entry],
        }

        self.write(data)

        result = load_ledger(self.path, "staging")

        self.assertEqual(
            result["001_categories.sql"]["status"],
            "APPLIED",
        )

    def test_reject_invalid_ledgers(self):
        cases = {
            "WRONG_VERSION": {
                **self.valid,
                "version": 2,
            },
            "WRONG_ENVIRONMENT": {
                **self.valid,
                "environment": "production",
            },
            "INVALID_ENTRIES": {
                **self.valid,
                "entries": "invalid",
            },
            "DUPLICATE_ENTRY": {
                **self.valid,
                "entries": [self.entry, self.entry],
            },
            "INVALID_CHECKSUM": {
                **self.valid,
                "entries": [{
                    **self.entry,
                    "sha256": "invalid",
                }],
            },
            "UNKNOWN_SEED": {
                **self.valid,
                "entries": [{
                    **self.entry,
                    "seed_file": "unknown.sql",
                }],
            },
            "INVALID_STATUS_TYPE": {
                **self.valid,
                "entries": [{
                    **self.entry,
                    "status": [],
                }],
            },
            "INCOMPLETE_ENTRY": {
                **self.valid,
                "entries": [{
                    "seed_file": "001_categories.sql",
                }],
            },
        }

        for name, data in cases.items():
            with self.subTest(case=name):
                self.write(data)

                with self.assertRaises(ValueError):
                    load_ledger(
                        self.path,
                        "staging",
                    )


if __name__ == "__main__":
    unittest.main()
