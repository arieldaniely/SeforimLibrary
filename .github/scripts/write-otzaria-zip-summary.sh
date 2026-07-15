#!/usr/bin/env bash
set -uo pipefail

SEFARIA_REPORT_PATH="${1:-build/incremental-sefaria-report.json}"
COPYRIGHT_REPORT_PATH="${2:-build/copyright-only-titles.json}"
COPYRIGHT_STATUS_PATH="${3:-build/copyright-report-status.json}"

{
  echo "# סיכום ייצוא ספריא ל־Otzaria ZIP"
  echo
  echo "## תוצאות הייצוא"
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
      ("| ספרים שיוצאו ל־ZIP | " + (.exportedBooks|tostring) + " |"),
      ("| קישורים שיוצאו | " + (.exportedLinks|tostring) + " |")
    ][]' "$SEFARIA_REPORT_PATH"
  else
    echo "| דוח הייצוא לא נוצר | 0 |"
  fi
  echo
  echo "## ספרים בעברית שהוסרו מייצוא ספריא עקב זכויות יוצרים"
  echo
  if [[ -s "$COPYRIGHT_REPORT_PATH" ]]; then
    copyright_count=$(jq 'length' "$COPYRIGHT_REPORT_PATH")
    if [[ -s "$COPYRIGHT_STATUS_PATH" ]]; then
      checked_count=$(jq '.hebrewTitlesChecked' "$COPYRIGHT_STATUS_PATH")
      error_count=$(jq '.requestErrors' "$COPYRIGHT_STATUS_PATH")
      version_count=$(jq '.copyrightHebrewVersions' "$COPYRIGHT_STATUS_PATH")
      echo "| מדד | כמות |"
      echo "|---|---:|"
      echo "| כותרים בעלי גרסה עברית שנבדקו מול API | $checked_count |"
      echo "| כותרים עבריים עם Copyright | $copyright_count |"
      echo "| גרסאות עבריות עם Copyright | $version_count |"
      echo "| שגיאות API | $error_count |"
      echo
    fi
    echo "<details><summary>הצגת רשימת הספרים</summary>"
    echo
    jq -r '.[] | "- " + .heTitle + " — " + ([.versions[].license] | unique | join(", "))' "$COPYRIGHT_REPORT_PATH"
    echo
    echo "</details>"
  else
    echo "דוח זכויות היוצרים בעברית לא נוצר."
  fi
  echo
  echo "<details><summary>דוגמאות לפריטים שסוננו ב־blacklist</summary>"
  echo
  if [[ -s "$SEFARIA_REPORT_PATH" ]]; then
    jq -r '(.bookBlacklistExamples + .authorBlacklistExamples) | if length == 0 then "- none" else map("- " + .)[] end' "$SEFARIA_REPORT_PATH"
  else
    echo "הדוח אינו זמין."
  fi
  echo
  echo "</details>"
} >> "$GITHUB_STEP_SUMMARY"
