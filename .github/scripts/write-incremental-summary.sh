#!/usr/bin/env bash
set -uo pipefail

DB_PATH="${1:-build/seforim.db}"
SEED_SOURCES_PATH="${2:-seed-source-counts.json}"
SEFARIA_REPORT_PATH="${3:-build/incremental-sefaria-report.json}"
SUPPLEMENTAL_REPORT_PATH="${4:-build/supplemental-hebrew-titles.json}"
SUPPLEMENTAL_STATUS_PATH="${5:-build/supplemental-report-status.json}"
INJECTED_BOOKS_PATH="${6:-build/injected-books.json}"
VALIDATION_PATH="${7:-build/injection-validation.json}"
SEED_TAG="${8:-לא ידוע}"
RELEASE_TAG="${9:-לא ידוע}"
PRERELEASE="${10:-לא ידוע}"
FINAL_SOURCES_PATH="${RUNNER_TEMP:-build}/final-source-counts.json"

seed_books="${SEED_BOOKS:-0}"
seed_lines="${SEED_LINES:-0}"
final_books=0
final_lines=0
if [[ -s "$DB_PATH" ]]; then
  final_books=$(sqlite3 "$DB_PATH" 'SELECT COUNT(*) FROM book;' 2>/dev/null || echo 0)
  final_lines=$(sqlite3 "$DB_PATH" 'SELECT COUNT(*) FROM line;' 2>/dev/null || echo 0)
  sqlite3 -json "$DB_PATH" 'SELECT s.name AS source, COUNT(*) AS books FROM book b JOIN source s ON s.id=b.sourceId GROUP BY s.id, s.name ORDER BY s.name;' > "$FINAL_SOURCES_PATH" 2>/dev/null || printf '[]\n' > "$FINAL_SOURCES_PATH"
else
  printf '[]\n' > "$FINAL_SOURCES_PATH"
fi
[[ -s "$SEED_SOURCES_PATH" ]] || printf '[]\n' > "$SEED_SOURCES_PATH"

{
  echo "# דוח מפורט — הזרקה incremental ויצירת release"
  echo
  echo "> Seed: \`$SEED_TAG\` · Release: \`$RELEASE_TAG\` · Pre-release: \`$PRERELEASE\`"
  echo
  echo "## 1. מצב מסד הנתונים לפני ואחרי"
  echo
  echo "| מדד | לפני | אחרי | שינוי |"
  echo "|---|---:|---:|---:|"
  echo "| ספרים | $seed_books | $final_books | $((final_books-seed_books)) |"
  echo "| שורות | $seed_lines | $final_lines | $((final_lines-seed_lines)) |"
  if [[ -s "$VALIDATION_PATH" ]]; then
    echo
    integrity=$(jq -r '.integrityCheck // "לא ידוע"' "$VALIDATION_PATH")
    fk=$(jq -r '.foreignKeyErrors // "לא ידוע"' "$VALIDATION_PATH")
    echo "> בדיקת תקינות SQLite: **$integrity** · הפרות foreign key: **$fk** · ספרים שנוספו ואומתו: **$(jq -r '.addedBooks // 0' "$VALIDATION_PATH")**"
  else
    echo
    echo "> ⚠️ קובץ אימות ההזרקה לא נוצר; ייתכן שההרצה נעצרה לפני בדיקות התקינות."
  fi

  echo
  echo "## 2. שינוי מלא לפי מקור"
  echo
  echo "| מקור | לפני | אחרי | שינוי |"
  echo "|---|---:|---:|---:|"
  jq -nr --slurpfile seed "$SEED_SOURCES_PATH" --slurpfile final "$FINAL_SOURCES_PATH" '
    (($seed[0] // []) + ($final[0] // []) | map(.source) | unique[]) as $name |
    ((($seed[0] // [])[] | select(.source == $name) | .books) // 0) as $before |
    ((($final[0] // [])[] | select(.source == $name) | .books) // 0) as $after |
    "| " + ($name|gsub("\\|"; "\\|")) + " | " + ($before|tostring) + " | " + ($after|tostring) + " | " + (($after-$before)|tostring) + " |"
  '

  echo
  echo "## 3. כל הספרים שנוספו למסד"
  echo
  if [[ -s "$INJECTED_BOOKS_PATH" ]]; then
    echo "נוספו **$(jq 'length' "$INJECTED_BOOKS_PATH")** ספרים. הרשימה הבאה מלאה ונקראת ישירות מהמסד לאחר ההזרקה."
    echo
    echo "### התפלגות הספרים שנוספו לפי מקור"
    echo
    echo "| מקור | ספרים | שורות | יוצאים | נכנסים |"
    echo "|---|---:|---:|---:|---:|"
    jq -r 'group_by(.source) | .[] | "| " + (.[0].source|gsub("\\|"; "\\|")) + " | " + (length|tostring) + " | " + (map(.totalLines)|add|tostring) + " | " + (map(.outboundLinks)|add|tostring) + " | " + (map(.inboundLinks)|add|tostring) + " |"' "$INJECTED_BOOKS_PATH"
    echo
    echo "<details open><summary>רשימת כל הספרים שנוספו — מידע מלא</summary>"
    echo
    echo "| ID | ספר | מקור | קטגוריה | מחברים | שורות | קישורים יוצאים | קישורים נכנסים |"
    echo "|---:|---|---|---|---|---:|---:|---:|"
    jq -r '.[] | "| " + (.id|tostring) + " | " + (.title|gsub("\\|"; "\\|")) + (if (.heRef // "") != "" then "<br><sub>`" + .heRef + "`</sub>" else "" end) + " | " + (.source|gsub("\\|"; "\\|")) + " | " + (.categoryPath|gsub("\\|"; "\\|")) + " | " + ((.authors // "")|gsub("\\|"; "\\|")) + " | " + (.totalLines|tostring) + " | " + (.outboundLinks|tostring) + " | " + (.inboundLinks|tostring) + " |"' "$INJECTED_BOOKS_PATH"
    echo
    echo "</details>"
  else
    echo "> קובץ רשימת הספרים שנוספו לא נוצר."
  fi

  echo
  echo "## 4. מסלול הייצוא מספריא לפני ההזרקה"
  echo
  if [[ -s "$SEFARIA_REPORT_PATH" ]]; then
    echo "מקור: **$(jq -r '.source // "לא צוין"' "$SEFARIA_REPORT_PATH")**"
    echo
    echo "| מדד | כמות |"
    echo "|---|---:|"
    jq -r '[
      ["סכמות במקור", (.schemasInSource // 0)],
      ["סכמות ללא merged.json", (.schemasWithoutMerged // 0)],
      ["ספרי merged.json במקור", (.mergedBooksInSource // 0)],
      ["כבר קיימים ב־seed", (.skippedAlreadyInSeed // 0)],
      ["נבחרו ל־parsing", (.selectedForParsing // 0)],
      ["parsing הצליח", (.parsedSuccessfully // 0)],
      ["כשלו ב־parsing", (.skippedParseFailure // 0)],
      ["סוננו לפי ספר", (.skippedByBookBlacklist // 0)],
      ["סוננו לפי מחבר", (.skippedByAuthorBlacklist // 0)],
      ["סה״כ סוננו", (.skippedByBlacklist // 0)],
      ["יוצאו להזרקה", (.exportedBooks // 0)],
      ["קישורים שיוצאו", (.exportedLinks // 0)],
      ["קישורים חיצוניים שלא נפתרו", (.unresolvedExternalLinks // 0)]
    ] | .[] | "| \(.[0]) | \(.[1]) |"' "$SEFARIA_REPORT_PATH"
    echo
    echo "<details><summary>כל הסכמות ללא merged.json ($(jq '(.schemasWithoutMergedTitles // [])|length' "$SEFARIA_REPORT_PATH"))</summary>"
    echo
    jq -r '(.schemasWithoutMergedTitles // []) | if length == 0 then "- אין" else .[] | "- `" + . + "`" end' "$SEFARIA_REPORT_PATH"
    echo
    echo "</details>"
    echo
    echo "<details><summary>כל הספרים שסוננו ב־blacklist</summary>"
    echo
    jq -r '(.bookBlacklistExamples // []) | if length == 0 then "- אין" else .[] | "- " + . end' "$SEFARIA_REPORT_PATH"
    echo
    echo "</details>"
    echo
    echo "<details><summary>כל הספרים שסוננו בגלל מחבר</summary>"
    echo
    jq -r '(.authorBlacklistExamples // []) | if length == 0 then "- אין" else .[] | "- " + . end' "$SEFARIA_REPORT_PATH"
    echo
    echo "</details>"
  else
    echo "> ⚠️ דוח הייצוא מספריא לא נוצר."
  fi

  echo
  echo "## 5. ספרים עבריים שחסרים ב־bulk export"
  echo
  if [[ -s "$SUPPLEMENTAL_STATUS_PATH" ]]; then
    echo "| מדד | כמות |"
    echo "|---|---:|"
    jq -r '[
      ["מועמדים", (.candidateTitles // 0)],
      ["בקשות API שהושלמו", (.checkedTitles // 0)],
      ["כותרים עם עברית", (.hebrewTitlesChecked // 0)],
      ["כותרים חסרים", (.supplementalHebrewTitles // 0)],
      ["גרסאות עבריות חסרות", (.supplementalHebrewVersions // 0)],
      ["Copyright בלבד", (.copyrightTitles // 0)],
      ["לא Copyright בלבד", (.nonCopyrightHebrewTitles // 0)],
      ["שגיאות API", (.requestErrors // 0)]
    ] | .[] | "| \(.[0]) | \(.[1]) |"' "$SUPPLEMENTAL_STATUS_PATH"
    echo
    echo "### התפלגות רישיונות מלאה"
    echo
    echo "| רישיון | גרסאות |"
    echo "|---|---:|"
    jq -r '(.licenseBreakdown // {}) | if length == 0 then "| אין נתונים | 0 |" else to_entries | sort_by(-.value,.key)[] | "| " + (.key|gsub("\\|"; "\\|")) + " | " + (.value|tostring) + " |" end' "$SUPPLEMENTAL_STATUS_PATH"
    echo
    echo "<details><summary>התפלגות קטגוריות מלאה</summary>"
    echo
    jq -r '(.categoryBreakdown // {}) | if length == 0 then "- אין נתונים" else to_entries | sort_by(-.value,.key)[] | "- **" + .key + "**: " + (.value|tostring) end' "$SUPPLEMENTAL_STATUS_PATH"
    echo
    echo "</details>"
    echo
    echo "<details open><summary>כל שגיאות ה־API</summary>"
    echo
    jq -r '(.errors // []) | if length == 0 then "- אין" else .[] | "- `" + .title + "`: " + .error end' "$SUPPLEMENTAL_STATUS_PATH"
    echo
    echo "</details>"
  else
    echo "> קובץ מצב בדיקת ה־API לא נוצר."
  fi

  if [[ -s "$SUPPLEMENTAL_REPORT_PATH" ]]; then
    echo
    echo "<details open><summary>כל הספרים החסרים — כל הגרסאות, הרישיונות והמקורות ($(jq 'length' "$SUPPLEMENTAL_REPORT_PATH"))</summary>"
    echo
    jq -r '.[] |
      "### [" + (.heTitle // .title) + "](https://www.sefaria.org.il/" + (.title|@uri) + ")\n\n" +
      "- Schema: `" + (.schemaTitle // "") + "`\n" +
      "- אנגלית: `" + (.title // "") + "`\n" +
      "- קטגוריות: " + ((.categories // [])|if length==0 then "לא צוינו" else join(" › ") end) + "\n" +
      "- סיווג: " + (if .copyrightOnly then "Copyright בלבד" else "קיימת גרסה שאינה Copyright" end) + "\n" +
      ((.versions // []) | to_entries | map(
        "  " + ((.key+1)|tostring) + ". **" + (.value.versionTitle // "ללא שם") + "**" +
        (if (.value.versionTitleInHebrew // "") != "" then " / " + .value.versionTitleInHebrew else "" end) +
        " — `" + (.value.license // "ללא רישיון") + "`" +
        (if (.value.actualLanguage // "") != "" then "; שפה: `" + .value.actualLanguage + "`" else "" end) +
        (if (.value.versionSource // "") != "" then "; [מקור](" + .value.versionSource + ")" else "" end)
      ) | join("\n"))' "$SUPPLEMENTAL_REPORT_PATH"
    echo
    echo "</details>"
  else
    echo
    echo "> רשימת הספרים החסרים לא נוצרה או שהיא ריקה."
  fi

  echo
  echo "## 6. נכסי ה־release"
  echo
  if [[ "$RELEASE_TAG" != "לא ידוע" && -n "${GITHUB_SERVER_URL:-}" && -n "${GITHUB_REPOSITORY:-}" ]]; then
    echo "> [פתיחת דף ה־release](${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/releases/tag/${RELEASE_TAG})"
    echo
  fi
  if [[ -d release-staging ]] && find release-staging -maxdepth 1 -type f -print -quit | grep -q .; then
    echo "| קובץ | גודל | SHA-256 |"
    echo "|---|---:|---|"
    while IFS= read -r asset; do
      size=$(du -h "$asset" | cut -f1)
      checksum=$(sha256sum "$asset" | cut -d' ' -f1)
      echo "| \`$(basename "$asset")\` | $size | \`$checksum\` |"
    done < <(find release-staging -maxdepth 1 -type f -print | sort)
  else
    echo "> תיקיית נכסי ה־release לא נוצרה או שהיא ריקה."
  fi
  echo
  echo "## 7. קובצי האבחון"
  echo
  echo "| קובץ | גודל | SHA-256 | תוכן |"
  echo "|---|---:|---|---|"
  while IFS='|' read -r path description; do
    if [[ -f "$path" ]]; then
      checksum=$(sha256sum "$path" | cut -d' ' -f1)
      echo "| \`$path\` | $(du -h "$path" | cut -f1) | \`$checksum\` | $description |"
    else
      echo "| \`$path\` | לא נוצר | — | $description |"
    fi
  done <<EOF
$SEFARIA_REPORT_PATH|דוח מלא של ייצוא וסינון ספריא
$SUPPLEMENTAL_REPORT_PATH|כל הכותרים החסרים ופרטי הגרסאות
$SUPPLEMENTAL_STATUS_PATH|התפלגויות ושגיאות API
$INJECTED_BOOKS_PATH|כל הספרים שנוספו ונתוניהם מהמסד
$VALIDATION_PATH|ספירות ובדיקות תקינות ההזרקה
new-book-ids.txt|כל מזהי הספרים החדשים שנשלחו ל־Lucene
build/otzaria-new-book-ids.txt|מזהי ספרי Otzaria שנוספו
EOF
} >> "$GITHUB_STEP_SUMMARY"
