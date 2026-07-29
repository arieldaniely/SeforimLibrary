#!/usr/bin/env bash
set -uo pipefail

DB_PATH="${1:-build/seforim.db}"
SEED_SOURCES_PATH="${2:-seed-source-counts.json}"
SEFARIA_REPORT_PATH="${3:-build/incremental-sefaria-report.json}"
FINAL_SOURCES_PATH="${RUNNER_TEMP:-build}/final-source-counts.json"
COPYRIGHT_REPORT_PATH="${4:-build/copyright-only-titles.json}"
if [[ ! -s "$COPYRIGHT_REPORT_PATH" && -d build/sefaria/export ]]; then
  discovered_copyright_report=$(find build/sefaria/export -name copyright-only-titles.json -type f -print -quit 2>/dev/null || true)
  [[ -z "$discovered_copyright_report" ]] || COPYRIGHT_REPORT_PATH="$discovered_copyright_report"
fi

seed_books="${SEED_BOOKS:-0}"
seed_lines="${SEED_LINES:-0}"
final_books=0
final_lines=0

if [[ -s "$DB_PATH" ]]; then
  final_books=$(sqlite3 "$DB_PATH" 'SELECT COUNT(*) FROM book;' 2>/dev/null || echo 0)
  final_lines=$(sqlite3 "$DB_PATH" 'SELECT COUNT(*) FROM line;' 2>/dev/null || echo 0)
  sqlite3 -json "$DB_PATH" \
    'SELECT s.name AS source, COUNT(*) AS books FROM book b JOIN source s ON s.id=b.sourceId GROUP BY s.id, s.name ORDER BY s.name;' \
    > "$FINAL_SOURCES_PATH"
else
  printf '[]\n' > "$FINAL_SOURCES_PATH"
fi
[[ -s "$SEED_SOURCES_PATH" ]] || printf '[]\n' > "$SEED_SOURCES_PATH"

{
  echo "# סיכום הזרקת ספרים"
  echo
  echo "| מדד | לפני | אחרי | שינוי |"
  echo "|---|---:|---:|---:|"
  echo "| ספרים | $seed_books | $final_books | $((final_books-seed_books)) |"
  echo "| שורות | $seed_lines | $final_lines | $((final_lines-seed_lines)) |"
  echo
  echo "## ספרים שנוספו לפי מקור"
  echo
  echo "| מקור | נוספו | סה״כ אחרי ההרצה |"
  echo "|---|---:|---:|"
  jq -nr --slurpfile seed "$SEED_SOURCES_PATH" --slurpfile final "$FINAL_SOURCES_PATH" '
    ($seed[0] // []) as $s | ($final[0] // [])[] as $f |
    (($s[] | select(.source == $f.source) | .books) // 0) as $before |
    select(($f.books - $before) != 0) |
    "| " + ($f.source | gsub("\\|"; "\\|")) + " | " + (($f.books-$before)|tostring) + " | " + ($f.books|tostring) + " |"
  '
  echo
  echo "## סינון מקור ספריא"
  echo
  echo "> הנתונים מתייחסים ל־bulk export. טקסטים שקיימים רק ב־API ולא נוצר עבורם merged.json — ובכללם גרסאות Copyright — אינם מגיעים למסננים המקומיים ולכן אינם נכללים במספרי הסינון."
  echo
  echo "| שלב | כמות |"
  echo "|---|---:|"
  if [[ -s "$SEFARIA_REPORT_PATH" ]]; then
    jq -r '[
      ("| ספרים עם merged.json במקור | " + (.mergedBooksInSource|tostring) + " |"),
      ("| דולגו כי כבר קיימים ב־seed | " + (.skippedAlreadyInSeed|tostring) + " |"),
      ("| נבחרו ל־parsing | " + (.selectedForParsing|tostring) + " |"),
      ("| parsing הצליח | " + (.parsedSuccessfully|tostring) + " |"),
      ("| כשלו ב־parsing | " + (.skippedParseFailure|tostring) + " |"),
      ("| סוננו ב־blacklist של ספר | " + (.skippedByBookBlacklist|tostring) + " |"),
      ("| סוננו ב־blacklist של מחבר | " + (.skippedByAuthorBlacklist|tostring) + " |"),
      ("| יוצאו להזרקה | " + (.exportedBooks|tostring) + " |"),
      ("| קישורים שיוצאו | " + (.exportedLinks|tostring) + " |")
    ][]' "$SEFARIA_REPORT_PATH"
  else
    echo "| דוח ספריא לא נוצר (השלב נכשל או טרם רץ) | 0 |"
  fi
  echo
  echo "## ספרים שהוסרו מייצוא ספריא עקב זכויות יוצרים"
  echo
  if [[ -s "$COPYRIGHT_REPORT_PATH" ]]; then
    copyright_count=$(jq 'length' "$COPYRIGHT_REPORT_PATH")
    echo "נמצאו **$copyright_count** כותרים שכל הגרסאות העבריות שלהם סומנו Copyright והוסרו מה־bulk export."
    echo
    echo "<details><summary>הצגת רשימת הספרים</summary>"
    echo
    jq -r '.[] | "- " + (._id // .title) + (if .versions then " — " + ([.versions[].license] | unique | join(", ")) else "" end)' "$COPYRIGHT_REPORT_PATH"
    echo
    echo "</details>"
  else
    echo "רשימת כותרי Copyright בעברית לא צורפה ל־bulk export; לא מוצגים כותרים שלא אומתו כרשומות עבריות."
  fi
  echo
  echo "<details><summary>דוגמאות לפריטים שסוננו ב־blacklist</summary>"
  echo
  if [[ -s "$SEFARIA_REPORT_PATH" ]]; then
    jq -r '
      (.bookBlacklistExamples + .authorBlacklistExamples) |
      if length == 0 then "- none" else map("- " + .)[] end
    ' "$SEFARIA_REPORT_PATH"
  else
    echo "הדוח אינו זמין."
  fi
  echo
  echo "</details>"
} >> "$GITHUB_STEP_SUMMARY"