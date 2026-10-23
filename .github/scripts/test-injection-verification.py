from contextlib import closing
import hashlib
import importlib.util
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


metadata = load("verify-injection-metadata")
download = load("verify-seed-download")


class InjectionVerificationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.database = self.root / "source.db"
        self.saved = self.root / "snapshot.db"
        with closing(sqlite3.connect(self.database)) as connection, connection:
            for table in metadata.TABLES:
                fields = "id INTEGER, name TEXT" if table != "author" else (
                    "id INTEGER, name TEXT, birthYear INTEGER, deathYear INTEGER, era TEXT"
                )
                connection.execute(f'CREATE TABLE "{table}" ({fields})')
            connection.execute("INSERT INTO author VALUES (1,'מחבר',1200,1280,'RI')")
            connection.execute("CREATE TABLE book(id INTEGER, title TEXT, heShortDesc TEXT)")
            connection.execute("INSERT INTO book VALUES(7,'ספר','תיאור')")

    def test_new_books_and_authors_are_allowed(self):
        metadata.snapshot(self.database, self.saved)
        with closing(sqlite3.connect(self.database)) as connection, connection:
            connection.execute("INSERT INTO author VALUES(2,'חדש',NULL,NULL,NULL)")
            connection.execute("INSERT INTO book VALUES(8,'חדש','נוסף')")
        self.assertEqual(1, metadata.verify(self.database, self.saved)["book"])

    def test_missing_author_or_changed_book_fails(self):
        metadata.snapshot(self.database, self.saved)
        with closing(sqlite3.connect(self.database)) as connection, connection:
            connection.execute("UPDATE book SET heShortDesc='שינוי' WHERE id=7")
        with self.assertRaisesRegex(ValueError, "book"):
            metadata.verify(self.database, self.saved)
        with closing(sqlite3.connect(self.database)) as connection, connection:
            connection.execute("DELETE FROM author")
        with self.assertRaisesRegex(ValueError, "author"):
            metadata.verify(self.database, self.saved)

    def test_old_schema_and_overwriting_snapshot_fail(self):
        metadata.snapshot(self.database, self.saved)
        with self.assertRaisesRegex(ValueError, "already exists"):
            metadata.snapshot(self.database, self.saved)
        with closing(sqlite3.connect(self.database)) as connection, connection:
            connection.execute("ALTER TABLE author RENAME TO old_author")
            connection.execute("CREATE TABLE author(id INTEGER, name TEXT)")
        with self.assertRaisesRegex(ValueError, "new upstream"):
            metadata.snapshot(self.database, self.root / "old.db")

    def test_download_checks_individual_parts_and_combined_archive(self):
        name = "seforim_bundle.tar.zst.part01"
        payload = b"fixture archive"
        (self.root / name).write_bytes(payload)
        digest = hashlib.sha256(payload).hexdigest()
        manifest = {"formatVersion": 1, "file": "seforim_bundle.tar.zst", "size": len(payload), "sha256": digest,
                    "parts": [{"file": name, "size": len(payload), "sha256": digest}]}
        path = self.root / "seforim_bundle.tar.zst.manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        self.assertEqual([name], download.verify(self.root))
        (self.root / name).write_bytes(b"corrupted data")
        with self.assertRaises(ValueError):
            download.verify(self.root)


if __name__ == "__main__":
    unittest.main()
