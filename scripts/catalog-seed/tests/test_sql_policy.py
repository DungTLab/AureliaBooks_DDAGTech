"""Unit tests for AureliaBooks SQL static policy."""

import sys
import unittest
from pathlib import Path

sys.path.insert(
    0,
    str(Path(__file__).resolve().parents[1])
)

from sql_policy import check_sql


class TestSqlPolicy(unittest.TestCase):

    FILE = "001_categories.sql"

    def test_block_unsafe_sql(self):
        cases = {
            "ALTER": (
                "ALTER TABLE categories ADD COLUMN code VARCHAR(50);"
            ),
            "DROP": (
                "DROP TABLE categories;"
            ),
            "DELETE": (
                "DELETE FROM categories;"
            ),
            "WRONG_TABLE": (
                "INSERT INTO authors (name) VALUES ('Test');"
            ),
            "MULTI_STATEMENT": (
                "INSERT INTO categories (name) VALUES ('Books');"
                "DELETE FROM authors;"
            ),
            "UPSERT": (
                "INSERT INTO categories (name) VALUES ('Books') "
                "ON DUPLICATE KEY UPDATE name='Other';"
            ),
            "INSERT_SELECT": (
                "INSERT INTO categories (name) "
                "SELECT email FROM users;"
            ),
            "LOAD_FILE": (
                "INSERT INTO categories (name) "
                "VALUES (LOAD_FILE('/etc/passwd'));"
            ),
            "EXECUTABLE_COMMENT": (
                "/*!50000 DROP TABLE categories */;"
            ),
            "MISSING_SEMICOLON": (
                "INSERT INTO categories (name) VALUES ('Books')"
            ),
            "UNCLOSED_STRING": (
                "INSERT INTO categories (name) VALUES ('Books);"
            ),
        }

        for name, sql in cases.items():
            with self.subTest(case=name):
                with self.assertRaises(ValueError):
                    check_sql(self.FILE, sql)

    def test_allow_supported_sql(self):
        cases = {
            "SIMPLE_INSERT": (
                "INSERT INTO categories (name) VALUES ('Books');"
            ),
            "SEMICOLON_IN_STRING": (
                "INSERT INTO categories (name) "
                "VALUES ('Art; Literature');"
            ),
            "KEYWORD_IN_STRING": (
                "INSERT INTO categories (name) "
                "VALUES ('DROP TABLE');"
            ),
            "LINE_COMMENT": (
                "-- DELETE FROM categories\n"
                "INSERT INTO categories (name) VALUES ('Books');"
            ),
            "BLOCK_COMMENT": (
                "/* ALTER TABLE categories */\n"
                "INSERT INTO categories (name) VALUES ('Books');"
            ),
        }

        for name, sql in cases.items():
            with self.subTest(case=name):
                result = check_sql(self.FILE, sql)
                self.assertEqual(
                    result,
                    "INSERT_ONLY (1 statements)"
                )

    def test_comment_only_template(self):
        sql = """
        -- AureliaBooks Catalog Seed
        -- TODO: Add approved records.
        """

        self.assertEqual(
            check_sql(self.FILE, sql),
            "TEMPLATE_ONLY"
        )


if __name__ == "__main__":
    unittest.main()
