# Seforim Importer Desktop

A small desktop application for adding local text books and JSON links to a
SeforimLibrary SQLite database. After import it rebuilds `catalog.pb` and both
Lucene indexes.

## Inputs

- Database: an existing `.db` file or a path for a new database.
- Books: either the source root, its Hebrew Otzaria child directory,
  or any directory containing `.txt` books.
- Links: a directory whose direct children are `.json` link files.
- Maximum memory: heap limit for each import/index subprocess (8 GB by default).

The importer accepts the metadata and manifest files from the parent of the
selected books directory when they are available. They remain optional, matching
the existing Otzaria importer behavior.

## Outputs

For a selected `library.db`, the application creates or updates:

- `library.db`
- `catalog.pb` in the database directory
- `library.db.lucene/`
- `library.db.lookup.lucene/`
- `library.db.buildstate`

Books already represented in the database are skipped. Link JSON files are
processed on every run. Acronym enrichment is intentionally skipped by the
standalone application so it never requires a network download.

## Run during development

```bash
./gradlew :importer-app:run
```

## Build the portable application

The primary distribution is a self-contained ZIP. It includes the application
and its Java runtime, so the destination computer does not need Java installed.

```powershell
./gradlew.bat :importer-app:packagePortableZip
```

The resulting archive is written under `build/distributions/`. Extract the
complete `seforim-importer` directory and run `seforim-importer.exe`. Do not
move the EXE away from its `app` and `runtime` directories.

## Optional installer

```powershell
./gradlew.bat :importer-app:packageMsi
```

## GitHub Actions

`.github/workflows/build-portable-importer.yml` runs the tests and builds the
portable Windows ZIP. The ZIP is uploaded as a workflow artifact.
