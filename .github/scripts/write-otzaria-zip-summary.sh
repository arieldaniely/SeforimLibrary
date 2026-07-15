#!/usr/bin/env bash
set -uo pipefail

SEFARIA_REPORT_PATH="${1:-build/incremental-sefaria-report.json}"
COPYRIGHT_REPORT_PATH="${2:-build/copyright-only-titles.json}"
COPYRIGHT_STATUS_PATH="${3:-build/copyright-report-status.json}"
COPYRIGHT_DOWNLOAD_STATUS_PATH="${4:-build/copyright-download-status.json}"
COPYRIGHT_OTZARIA_REPORT_PATH="${5:-build/copyright-otzaria-report.json}"
COPYRIGHT_IMPORT_VALIDATION_PATH="${6:-build/copyright-import-validation.json}"

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
  if [[ -s "$COPYRIGHT_DOWNLOAD_STATUS_PATH" && -s "$COPYRIGHT_OTZARIA_REPORT_PATH" ]]; then
    echo
    echo "## ZIP נפרד של ספרי Copyright בעברית"
    echo
    requested=$(jq '.requestedBooks' "$COPYRIGHT_DOWNLOAD_STATUS_PATH")
    downloaded=$(jq '.downloadedBooks' "$COPYRIGHT_DOWNLOAD_STATUS_PATH")
    download_errors=$(jq '.errors | length' "$COPYRIGHT_DOWNLOAD_STATUS_PATH")
    versions=$(jq '.downloadedHebrewVersions' "$COPYRIGHT_DOWNLOAD_STATUS_PATH")
    direct_links=$(jq '.directBulkLinks' "$COPYRIGHT_DOWNLOAD_STATUS_PATH")
    api_links=$(jq '.downloadedApiLinks' "$COPYRIGHT_DOWNLOAD_STATUS_PATH")
    unresolved_api_links=$(jq '.unresolvedDirectLinks // 0' "$COPYRIGHT_DOWNLOAD_STATUS_PATH")
    exported=$(jq '.exportedBooks' "$COPYRIGHT_OTZARIA_REPORT_PATH")
    blacklisted=$(jq '.skippedByBlacklist' "$COPYRIGHT_OTZARIA_REPORT_PATH")
    links=$(jq '.exportedLinks' "$COPYRIGHT_OTZARIA_REPORT_PATH")
    unresolved=$(jq '.unresolvedExternalLinks' "$COPYRIGHT_OTZARIA_REPORT_PATH")
    echo "| מדד | כמות |"
    echo "|---|---:|"
    echo "| ספרים שהתבקשו מה־API | $requested |"
    echo "| ספרים שהורדו | $downloaded |"
    echo "| כשלי הורדת ספרים לאחר ניסיונות חוזרים | $download_errors |"
    echo "| גרסאות עבריות שהורדו ומוזגו | $versions |"
    echo "| קישורים ישירים שנמצאו ב־bulk CSV | $direct_links |"
    echo "| קישורים ישירים שקיבלו מראה־מקום עברי מה־API | $api_links |"
    echo "| קישורים ישירים שלא נפתרו ב־API | $unresolved_api_links |"
    echo "| ספרים שיוצאו ל־ZIP הנפרד | $exported |"
    echo "| ספרי Copyright שסוננו ברשימה השחורה | $blacklisted |"
    echo "| קישורים שיוצאו בפורמט אוצריא | $links |"
    echo "| קישורים שלא נמצא להם יעד ב־seed | $unresolved |"
    echo
    if [[ -s "$COPYRIGHT_IMPORT_VALIDATION_PATH" ]] && [[ "$(jq -r '.verified' "$COPYRIGHT_IMPORT_VALIDATION_PATH")" == "true" ]]; then
      added=$(jq '.addedBooks' "$COPYRIGHT_IMPORT_VALIDATION_PATH")
      outbound_to_seed=$(jq '.outboundLinksToSeed' "$COPYRIGHT_IMPORT_VALIDATION_PATH")
      echo "> ה־ZIP אומת באמצעות importer של אוצריא: נוספו $added ספרים ונוצרו $outbound_to_seed קישורים מהם לספרים שכבר היו ב־seed DB."
    else
      echo "> אימות ה־ZIP באמצעות importer של אוצריא לא הושלם בהצלחה."
    fi
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
