"""Preserve upstream author/book metadata while appending approved books.

Only metadata is copied; the multi-gigabyte line corpus is never duplicated.
Run snapshot before injection and verify before packaging.
"""
from contextlib import closing
import argparse
import json
import sqlite3
from pathlib import Path

TABLES = ("author", "author_alias", "author_bio", "book_author", "license", "edition", "book_edition",
          "pub_place", "pub_date", "book_pub_place", "book_pub_date")


def quote(name):
    return '"' + name.replace('"', '""') + '"'


def columns(connection, table):
    return [row[1] for row in connection.execute(f"PRAGMA table_info({quote(table)})")]


def snapshot(database, output):
    if output.exists():
        raise ValueError("Snapshot already exists")
    with closing(sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True)) as source:
        if not {"birthYear", "deathYear", "era"} <= set(columns(source, "author")):
            raise ValueError("The seed database must include the new upstream author metadata")
        for table in TABLES:
            if not columns(source, table):
                raise ValueError(f"Missing metadata table: {table}")
        with closing(sqlite3.connect(output)) as target, target:
            for table in (*TABLES, "book"):
                names = columns(source, table) if table != "book" else ["id", "title", "heShortDesc"]
                selection = ",".join(map(quote, names))
                target.execute(f"CREATE TABLE {quote(table)} ({selection})")
                rows = source.execute(f"SELECT {selection} FROM {quote(table)}")
                target.executemany(f"INSERT INTO {quote(table)} VALUES ({','.join('?' for _ in names)})", rows)


def verify(database, saved):
    if not saved.is_file():
        raise ValueError("Missing metadata snapshot")
    counts = {}
    with closing(sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True)) as connection:
        connection.execute("ATTACH DATABASE ? AS seed", (saved.resolve().as_uri() + "?mode=ro",))
        for table in (*TABLES, "book"):
            names = [row[1] for row in connection.execute(f"PRAGMA seed.table_info({quote(table)})")]
            if not names:
                raise ValueError(f"Incomplete snapshot: {table}")
            selection = ",".join(map(quote, names))
            missing = connection.execute(
                f"SELECT {selection} FROM seed.{quote(table)} EXCEPT SELECT {selection} FROM main.{quote(table)} LIMIT 1"
            ).fetchone()
            if missing is not None:
                raise ValueError(f"Upstream metadata changed or removed: {table}")
            counts[table] = connection.execute(f"SELECT count(*) FROM seed.{quote(table)}").fetchone()[0]
    return counts


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("snapshot", "verify"))
    parser.add_argument("database", type=Path)
    parser.add_argument("snapshot", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if args.operation == "snapshot":
        snapshot(args.database, args.snapshot)
    else:
        counts = verify(args.database, args.snapshot)
        report = {"preserved": True, "seedMetadataRows": counts}
        if args.report:
            args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report))
