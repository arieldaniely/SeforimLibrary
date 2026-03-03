# Seforim Book Importer (Portable Desktop GUI)

Portable desktop tool (Compose for Desktop, JVM) for appending new books into an existing `seforim.db`.

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

Portable bundle (no installer):

```bash
./gradlew :bookimporter:packagePortable
# or from root
./gradlew packageBookImporterPortable
```

## User usage (portable)

1. Build or copy the generated ZIP from `generator/bookimporter/build/compose/binaries/main-release/portable/` (or use the app folder in `.../app/`).
2. Extract and move the folder anywhere you want (USB, shared drive, etc.).
3. Run the launcher inside that folder.
4. Select DB + book folder(s), run **Scan + Preview**, then **Execute Import**.


### Legacy command compatibility

`packageBookImporterWindowsExe` and `:bookimporter:packageWindowsExe` are kept as compatibility aliases and now build the portable bundle.
