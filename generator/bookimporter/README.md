# Seforim Book Importer (Desktop GUI)

Desktop tool (Compose for Desktop, JVM) for appending new books into an existing `seforim.db`.

## Features

- Select an existing SQLite DB (`.db`) and one or more library root folders.
- Scan + preview before execution (detected books/categories, invalid roots).
- Execute import flow with progress + logs:
  1. Backup DB (`*.before-import.bak`)
  2. Append lines/books via Otzaria pipeline
  3. Append links
  4. Optional post-steps: rebuild `catalog.pb`, rebuild Lucene indexes
- Rollback on failure (restore DB from backup).

## Run (developer)

```bash
./gradlew :bookimporter:run
```

## Build distributables

Cross-platform artifacts (on matching OS):

```bash
./gradlew :bookimporter:packageReleaseDistribution
```

Windows portable ZIP (run on Windows):

```bash
./gradlew :bookimporter:packageWindowsPortable
# or from root
./gradlew packageBookImporterWindowsPortable
```

## User run (Windows portable)

1. Download the generated ZIP from `generator/bookimporter/build/compose/binaries/main-release/portable/`.
2. Extract the ZIP to any folder.
3. Open **SeforimBookImporter.exe** from the extracted folder.
4. Select DB + book folder(s), run **Scan + Preview**, then **Execute Import**.
