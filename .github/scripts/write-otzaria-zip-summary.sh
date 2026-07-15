#!/usr/bin/env bash
set -uo pipefail

SEFARIA_REPORT_PATH="${1:-build/incremental-sefaria-report.json}"
SUPPLEMENTAL_REPORT_PATH="${2:-build/supplemental-hebrew-titles.json}"
SUPPLEMENTAL_STATUS_PATH="${3:-build/supplemental-report-status.json}"
DOWNLOAD_STATUS_PATH="${4:-build/supplemental-download-status.json}"
OTZARIA_REPORT_PATH="${5:-build/supplemental-otzaria-report.json}"
IMPORT_VALIDATION_PATH="${6:-build/supplemental-import-validation.json}"
SEED_TAG="${7:-לא ידוע}"
ARTIFACT_NAME="${8:-לא ידוע}"
SUPPLEMENTAL_ENABLED="${9:-לא ידוע}"

md_escape() { sed -e 's/|/\\|/g' -e ':a;N;$!ba;s/\r\{0,1\}\n/ /g'; }
json_value() { jq -r "$2 // 0" "$1" 2>/dev/null || printf 'לא זמין'; }

{
  echo "# דוח מפורט — ייצוא ספריא ל־Otzaria ZIP"
  echo
  echo "> Seed: \`$SEED_TAG\` · Artifact: \`$ARTIFACT_NAME\` · ZIP משלים הופעל: \`$SUPPLEMENTAL_ENABLED\`"
  echo
  echo "הדוח מפריד בין הייצוא הרגיל של ספריא לבין ספרים עבריים שחסרים בו ונמצאו דרך ה־API. כל קובצי ה־JSON הגולמיים מצורפים ל־artifacts לצורך בדיקה מלאה."
  echo
  echo "## 1. ייצוא רגיל מול מסד ה־seed"
  echo
  if [[ -s "$SEFARIA_REPORT_PATH" ]]; then
    echo "מקור: **$(jq -r '.source // "לא צוין"' "$SEFARIA_REPORT_PATH" | md_escape)**"
    echo
    echo "| מדד | כמות | משמעות |"
    echo "|---|---:|---|"
    jq -r '[
      ["סכמות במקור", (.schemasInSource // 0), "כל הכותרים שזוהו בייצוא"],
      ["סכמות ללא merged.json", (.schemasWithoutMerged // 0), "מועמדים לבדיקה מול API"],
      ["ספרי merged.json במקור", (.mergedBooksInSource // 0), "ספרים זמינים לטעינה מהייצוא"],
      ["כבר קיימים ב־seed", (.skippedAlreadyInSeed // 0), "דולגו כדי לשמור על ייצוא incremental"],
      ["נבחרו ל־parsing", (.selectedForParsing // 0), "קובצי merged.json שנקראו"],
      ["parsing הצליח", (.parsedSuccessfully // 0), "ספרים שפוענחו למבנה פנימי"],
      ["כשלו ב־parsing", (.skippedParseFailure // 0), "קבצים שלא ניתן היה לפענח"],
      ["סוננו ברשימות שחורות", (.skippedByBlacklist // 0), "סה״כ סינון ספר ומחבר"],
      ["סוננו לפי ספר", (.skippedByBookBlacklist // 0), "התאמה לרשימת הספרים"],
      ["סוננו לפי מחבר", (.skippedByAuthorBlacklist // 0), "התאמה לרשימת המחברים"],
      ["ספרים שיוצאו", (.exportedBooks // 0), "קובצי הטקסט ב־ZIP הרגיל"],
      ["קישורים שיוצאו", (.exportedLinks // 0), "קישורים בפורמט Otzaria"],
      ["קישורים חיצוניים שלא נפתרו", (.unresolvedExternalLinks // 0), "יעדים שלא נמצאו ב־seed"]
    ] | .[] | "| \(.[0]) | \(.[1]) | \(.[2]) |"' "$SEFARIA_REPORT_PATH"
    echo
    echo "<details><summary>כל הסכמות ללא merged.json ($(jq '(.schemasWithoutMergedTitles // []) | length' "$SEFARIA_REPORT_PATH"))</summary>"
    echo
    jq -r '(.schemasWithoutMergedTitles // []) | if length == 0 then "- אין" else .[] | "- `" + . + "`" end' "$SEFARIA_REPORT_PATH"
    echo
    echo "</details>"
    echo
    echo "<details><summary>רשימת שמות הסכמות ללא merged.json (שדה תאימות מלא)</summary>"
    echo
    jq -r '(.schemasWithoutMergedExamples // []) | if length == 0 then "- אין" else .[] | "- `" + . + "`" end' "$SEFARIA_REPORT_PATH"
    echo
    echo "</details>"
    echo
    echo "<details><summary>כל הפריטים שסוננו ברשימות השחורות</summary>"
    echo
    echo "### סינון לפי ספר"
    jq -r '(.bookBlacklistExamples // []) | if length == 0 then "- אין" else .[] | "- " + . end' "$SEFARIA_REPORT_PATH"
    echo
    echo "### סינון לפי מחבר"
    jq -r '(.authorBlacklistExamples // []) | if length == 0 then "- אין" else .[] | "- " + . end' "$SEFARIA_REPORT_PATH"
    echo
    echo "</details>"
  else
    echo "> ⚠️ קובץ דוח הייצוא הרגיל לא נוצר; ההרצה נעצרה לפני השלמת שלב זה."
  fi

  echo
  echo "## 2. איתור ספרים עבריים שחסרים בייצוא"
  echo
  if [[ -s "$SUPPLEMENTAL_STATUS_PATH" ]]; then
    echo "| מדד | כמות |"
    echo "|---|---:|"
    jq -r '[
      ["מועמדים שנמצאו בדוח הייצוא", (.candidateTitles // 0)],
      ["בקשות API שהושלמו", (.checkedTitles // 0)],
      ["כותרים עם גרסה עברית", (.hebrewTitlesChecked // 0)],
      ["כותרים עבריים חסרים להשלמה", (.supplementalHebrewTitles // 0)],
      ["גרסאות עבריות חסרות", (.supplementalHebrewVersions // 0)],
      ["כותרים שכל גרסאותיהם Copyright", (.copyrightTitles // 0)],
      ["גרסאות Copyright", (.copyrightHebrewVersions // 0)],
      ["כותרים שאינם Copyright בלבד", (.nonCopyrightHebrewTitles // 0)],
      ["שגיאות בבדיקת API", (.requestErrors // 0)]
    ] | .[] | "| \(.[0]) | \(.[1]) |"' "$SUPPLEMENTAL_STATUS_PATH"
    echo
    echo "### התפלגות רישיונות"
    echo
    echo "| רישיון | מספר גרסאות |"
    echo "|---|---:|"
    jq -r '(.licenseBreakdown // {}) | if length == 0 then "| אין נתונים | 0 |" else to_entries | sort_by(-.value, .key)[] | "| " + (.key|tostring|gsub("\\|"; "\\|")) + " | " + (.value|tostring) + " |" end' "$SUPPLEMENTAL_STATUS_PATH"
    echo
    echo "<details><summary>התפלגות קטגוריות של הספרים החסרים</summary>"
    echo
    echo "| קטגוריה | מופעים |"
    echo "|---|---:|"
    jq -r '(.categoryBreakdown // {}) | if length == 0 then "| אין נתונים | 0 |" else to_entries | sort_by(-.value, .key)[] | "| " + (.key|tostring|gsub("\\|"; "\\|")) + " | " + (.value|tostring) + " |" end' "$SUPPLEMENTAL_STATUS_PATH"
    echo
    echo "</details>"
    echo
    echo "<details open><summary>שגיאות API בבדיקת הכותרים ($(jq '(.errors // []) | length' "$SUPPLEMENTAL_STATUS_PATH"))</summary>"
    echo
    jq -r '(.errors // []) | if length == 0 then "- אין שגיאות" else .[] | "- `" + (.title // "ללא כותרת") + "`: " + (.error // "שגיאה לא ידועה") end' "$SUPPLEMENTAL_STATUS_PATH"
    echo
    echo "</details>"
  else
    echo "> ⚠️ קובץ מצב בדיקת הכותרים לא נוצר."
  fi

  if [[ -s "$SUPPLEMENTAL_REPORT_PATH" ]]; then
    echo
    echo "<details open><summary>כל הספרים העבריים החסרים ($(jq 'length' "$SUPPLEMENTAL_REPORT_PATH")) — גרסאות, רישיונות ומקורות</summary>"
    echo
    jq -r '.[] |
      "### [" + (.heTitle // .title) + "](https://www.sefaria.org.il/" + (.title | @uri) + ")\n\n" +
      "- כותרת סכימה: `" + (.schemaTitle // "") + "`\n" +
      "- כותרת אנגלית: `" + (.title // "") + "`\n" +
      "- קטגוריות: " + ((.categories // []) | if length == 0 then "לא צוינו" else join(" › ") end) + "\n" +
      "- סיווג: " + (if .copyrightOnly then "כל הגרסאות העבריות מסומנות Copyright" else "קיימת לפחות גרסה עברית שאינה Copyright" end) + "\n" +
      "- מספר גרסאות עבריות: " + ((.versions // []) | length | tostring) + "\n\n" +
      ((.versions // []) | to_entries | map(
        "  " + ((.key + 1)|tostring) + ". **" + (.value.versionTitle // "ללא שם גרסה") + "**" +
        (if (.value.versionTitleInHebrew // "") != "" then " / " + .value.versionTitleInHebrew else "" end) +
        " — רישיון: `" + (.value.license // "לא צוין") + "`" +
        (if (.value.actualLanguage // "") != "" then "; שפה בפועל: `" + .value.actualLanguage + "`" else "" end) +
        (if (.value.versionSource // "") != "" then "; [מקור הגרסה](" + .value.versionSource + ")" else "" end)
      ) | join("\n"))' "$SUPPLEMENTAL_REPORT_PATH"
    echo
    echo "</details>"
  else
    echo
    echo "> לא נוצרה רשימת ספרים משלימים, או שלא נמצאו ספרים כאלה."
  fi

  echo
  echo "## 3. הורדה, סינון וקישורים של ה־ZIP המשלים"
  echo
  if [[ -s "$DOWNLOAD_STATUS_PATH" ]]; then
    echo "| מדד | כמות |"
    echo "|---|---:|"
    jq -r '[
      ["ספרים שהתבקשו", (.requestedBooks // 0)],
      ["סוננו לפני הורדה", ((.blacklistedBeforeDownload // [])|length)],
      ["זכאים לאחר blacklist", (.eligibleAfterBlacklist // 0)],
      ["ספרים שהורדו", (.downloadedBooks // 0)],
      ["גרסאות עבריות שהורדו ומוזגו", (.downloadedHebrewVersions // 0)],
      ["קישורים ישירים ב־bulk CSV", (.directBulkLinks // 0)],
      ["קישורים שקיבלו מראה־מקום עברי", (.downloadedApiLinks // 0)],
      ["קישורים שלא נפתרו", (.unresolvedDirectLinks // 0)],
      ["כשלי הורדת ספר", ((.errors // [])|length)]
    ] | .[] | "| \(.[0]) | \(.[1]) |"' "$DOWNLOAD_STATUS_PATH"
    echo
    echo "<details open><summary>תוצאה לכל ספר שהורד</summary>"
    echo
    echo "| ספר | Schema | גרסאות | קישורים | לא נפתרו |"
    echo "|---|---|---:|---:|---:|"
    jq -r '(.completedBooks // []) | if length == 0 then "| אין ספרים שהושלמו | | 0 | 0 | 0 |" else .[] | "| " + (.heTitle // "") + " | `" + (.schemaTitle // "") + "` | " + ((.downloadedVersions // 0)|tostring) + " | " + ((.resolvedLinks // 0)|tostring) + " | " + ((.unresolvedLinks // 0)|tostring) + " |" end' "$DOWNLOAD_STATUS_PATH"
    echo
    echo "</details>"
    echo
    echo "<details open><summary>ספרים שסוננו לפני ההורדה</summary>"
    echo
    jq -r '(.blacklistedBeforeDownload // []) | if length == 0 then "- אין" else .[] | "- **" + (.heTitle // .schemaTitle) + "** (`" + (.schemaTitle // "") + "`) — סיבות: " + ((.reasons // [])|join(", ")) end' "$DOWNLOAD_STATUS_PATH"
    echo
    echo "</details>"
    echo
    echo "<details open><summary>כשלי הורדת ספרים</summary>"
    echo
    jq -r '(.errors // []) | if length == 0 then "- אין" else .[] | "- **" + (.heTitle // "ללא כותרת") + "**: " + (.error // "שגיאה לא ידועה") end' "$DOWNLOAD_STATUS_PATH"
    echo
    echo "</details>"
    echo
    echo "<details><summary>כל הקישורים שלא נפתרו</summary>"
    echo
    jq -r '(.linkErrors // []) | if length == 0 then "- אין" else .[] | "- **" + (.heTitle // "") + "** — `" + (.anchorRef // .sectionRef // "") + "` → `" + (.sourceRef // "") + "`: " + (.error // "שגיאה לא ידועה") end' "$DOWNLOAD_STATUS_PATH"
    echo
    echo "</details>"
  elif [[ "$SUPPLEMENTAL_ENABLED" == "true" ]]; then
    echo "> שלב ההורדה לא רץ (אין מועמדים) או נעצר לפני יצירת קובץ המצב."
  else
    echo "> יצירת ZIP משלים לא הופעלה בהרצה זו. דוח האיתור עדיין נוצר ומציג מה היה ניתן להשלים."
  fi

  echo
  echo "## 4. תוצאת הייצוא והאימות של ה־ZIP המשלים"
  echo
  if [[ -s "$OTZARIA_REPORT_PATH" ]]; then
    echo "| מדד | כמות |"
    echo "|---|---:|"
    jq -r '[
      ["נבחרו ל־parsing", (.selectedForParsing // 0)],
      ["parsing הצליח", (.parsedSuccessfully // 0)],
      ["כשלי parsing", (.skippedParseFailure // 0)],
      ["כבר קיימים ב־seed", (.skippedAlreadyInSeed // 0)],
      ["סוננו ברשימות שחורות", (.skippedByBlacklist // 0)],
      ["ספרים שיוצאו ל־ZIP", (.exportedBooks // 0)],
      ["קישורים שיוצאו", (.exportedLinks // 0)],
      ["קישורים חיצוניים שלא נפתרו", (.unresolvedExternalLinks // 0)]
    ] | .[] | "| \(.[0]) | \(.[1]) |"' "$OTZARIA_REPORT_PATH"
  else
    echo "> לא נוצר דוח ייצוא משלים."
  fi
  echo
  if [[ -s "$IMPORT_VALIDATION_PATH" ]]; then
    verified=$(jq -r '.verified // false' "$IMPORT_VALIDATION_PATH")
    expected=$(json_value "$IMPORT_VALIDATION_PATH" '.expectedBooks // .addedBooks')
    added=$(json_value "$IMPORT_VALIDATION_PATH" '.addedBooks')
    importer_ids=$(json_value "$IMPORT_VALIDATION_PATH" '.importerIds // .addedBooks')
    unique_ids=$(json_value "$IMPORT_VALIDATION_PATH" '.uniqueImporterIds // .addedBooks')
    colliding_ids=$(json_value "$IMPORT_VALIDATION_PATH" '.collidingSeedIds')
    outbound=$(json_value "$IMPORT_VALIDATION_PATH" '.outboundLinksToSeed')
    state_sha=$(jq -r '.buildStateSha256 // "לא נרשם"' "$IMPORT_VALIDATION_PATH")
    if [[ "$verified" == "true" ]]; then
      echo "> ✅ ה־ZIP המשלים אומת באמצעות importer אמיתי של Otzaria: צפויים **$expected**, נוספו **$added**, נרשמו **$importer_ids** מזהים (**$unique_ids** ייחודיים), התנגשויות מול seed: **$colliding_ids**, וקישורים לספרי seed: **$outbound**."
      echo "> buildstate של ה־seed: \`$state_sha\`"
    else
      validation_error=$(jq -r '.error // "סיבת הכשל לא נרשמה"' "$IMPORT_VALIDATION_PATH")
      echo "> ❌ אימות ה־ZIP נכשל: $validation_error. צפויים **$expected**, נוספו **$added**, נרשמו **$importer_ids** מזהים (**$unique_ids** ייחודיים), התנגשויות מול seed: **$colliding_ids**."
    fi
  else
    echo "> אימות ה־ZIP המשלים לא בוצע או לא הושלם."
  fi

  echo
  echo "## 5. קובצי אבחון שנוצרו בהרצה"
  echo
  echo "| קובץ | גודל | SHA-256 | תוכן |"
  echo "|---|---:|---|---|"
  while IFS='|' read -r path description; do
    if [[ -f "$path" ]]; then
      size=$(du -h "$path" | cut -f1)
      checksum=$(sha256sum "$path" | cut -d' ' -f1)
      echo "| \`$path\` | $size | \`$checksum\` | $description |"
    else
      echo "| \`$path\` | לא נוצר | — | $description |"
    fi
  done <<EOF
build/seed-seforim.db.buildstate|מצב הקצאת המזהים של מסד ה־seed
build/incremental-sefaria-otzaria.zip|ה־ZIP הרגיל שנוצר
build/supplemental-sefaria-otzaria.zip|ה־ZIP המשלים שנוצר
$SEFARIA_REPORT_PATH|דוח הייצוא הרגיל ורשימות המועמדים
$SUPPLEMENTAL_REPORT_PATH|כל הספרים החסרים, הגרסאות, הרישיונות והמקורות
$SUPPLEMENTAL_STATUS_PATH|סטטוס סריקת API, התפלגויות ושגיאות
$DOWNLOAD_STATUS_PATH|סטטוס הורדה, סינון וקישורים לכל ספר
build/supplemental-merged-files.txt|רשימת קובצי merged שהועברו לייצוא
build/supplemental-api-links.json|כל קישורי ה־API שנאספו
$OTZARIA_REPORT_PATH|דוח הייצוא של ה־ZIP המשלים
$IMPORT_VALIDATION_PATH|תוצאת אימות importer מול מסד ה־seed
EOF
  echo
  echo "_הערה: רשימת ״סכמות ללא merged.json״ היא רשימת מועמדים רחבה. רשימת ״הספרים העבריים החסרים״ היא התוצאה המאומתת מול גרסאות ה־API של ספריא._"
} >> "$GITHUB_STEP_SUMMARY"
