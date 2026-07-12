<div dir="rtl">

# תוכנית יישום מדורגת — הלינקר כשכבה יציבה ואינקרמנטלית

מסמך-מימוש מעשי (משלים את `LINKER_DELTA_PLAN.md` — הרציונל הארכיטקטוני). כל שלב:
**מטרה · צעדים · קבצים · קריטריון-קבלה**. מסודר כך שמפתח זוטר יוכל לממש נקי ויעיל.

## תוצאות אימות (בוצע לפני המימוש — מגדיר את התוכנית)

- **כיסוי `resolveRefs`: ~99% (proxy, לא הוכחה סופית).** האימות היה **proxy ב-Python**
  (`Ref(...).all_segment_refs()` + קיום-ספר ב-DB): 98.4% distinct / 99.3% משוקלל, וכל
  הצורות שהפותר-Python נכשל עליהן (Tosafot flat, ר"ן, ירושלמי, Arakhin) **נפתרות** —
  כי הן refs אנגליים-קנוניים מול RefEntry אנגלי-קנוני. **מסקנה: לאחסן target_ref
  אנגלי; לתת ל-resolveRefs לפתור בבנייה.** ⚠️ **הוכחה סופית נדרשת** — JVM test שמריץ
  את `SefariaImportRefs.resolveRefs` האמיתי על sidecar (קריטריון-קבלה בשלב 5).
- **דו-משמעיות: 13.5%.** מתוכן 16.5% אותו-ספר (סגמנט לא ודאי), 83.5% צולבות
  (בבלי↔ירושלמי, משנה↔גמרא). ‏thoroughness=HIGH לא עוזר (נבדק). **מדיניות:
  לזרוק דו-משמעי** (ברירת-מחדל בטוחה, ~86.5% נשמרים); מוסכמת "בבלי/משנה" =
  flag אופציונלי (החלטת-משתמש).

---

## החלטות מוקפאות (מהמשתמש)

מקשרים ממקורות **ספריא + otzaria**; אחסון ב**רפו ייעודי**; importer ב-**Phase-2
+ sidecar**; ‏line-id של otzaria נשאר content-hash (churn מקובל); ‏LINKER
**חד-כיווני** (בלי מראת-SOURCE); דו-משמעי **נזרק** כברירת-מחדל.

> **הערה על bootstrap:** ההרצה הקודמת (2.16M) הייתה על DB v9 ישן. לכן: **קודם
> מתקנים את כל הקוד (שלבים 1–5), ורק אז מריצים bootstrap מחדש על הנתונים
> העדכניים** (שלב 6). אין טעם לשמר את הפלט הישן.

---

## שלב 0 — תנאי-סף מחייבים (לפני כל קוד לינקר)

**מטרה:** לתקן שני חסמי-תשתית שבלעדיהם השאר לא עובד.

**צעדים:**
1. **תיקון באג `SourceHashComputer.kt` (חוסם — ה-delta של otzaria מת כרגע):** ה-regex
   (`generator/common/.../changes/SourceHashComputer.kt:106`) מצפה לפורמט **שטוח**
   ‏`{path:"hash"}`, בעוד `files_manifest.json` האמיתי **מקונן** `{path:{"hash":...}}` →
   מחזיר מפה ריקה → זיהוי-השינוי של otzaria בגנרטור **no-op**. לתקן את ה-regex לפרסר
   מקונן (כמו `Generator.loadSourcesFromManifest` שכבר עושה נכון), + לתקן את ה-fixtures
   ב-`SourceHashComputerTest.kt` (כרגע בפורמט השגוי → הטסטים "עוברים" סרק).
2. **‏SefariaExport:** לפרסם `changelog_diff.json` כ-release-asset (הוספה ל-`UPLOADS`
   ב-`22_generate_changelog.sh`) — נדרש מכונתית ל-rename detection.
3. **משימת `dumpLines` ב-SeforimLibrary:** נקודת-כניסה שמפיקה `book_key → [line_content
   מנוקה בסדר lineIndex]` דרך **אותו** קוד import/ניקוי של הבנייה (ראו שלב 2, מקור-השורות).

**קבלה:** ‏`OtzariaSourceHashComputer.compute()` מחזיר מפה לא-ריקה על manifest אמיתי;
טסט delta של otzaria מזהה ספר שהשתנה; `changelog_diff.json` יורד כ-asset; `dumpLines`
מפיק snapshot שבו `content[start:end]` של ציטוט = מה שה-DB יאחסן.

---

## שלב 1 — רפו ייעודי + פורמט הארטיפקט

**מטרה:** מקום-אמת יחיד לקישורים הגולמיים (ref-based), עם lineage וניהול-בסיס.

**צעדים:**
1. ליצור רפו `otzaria-linker-links`. מבנה:
   ```
   artifacts/<sourceBookKey>.jsonl      # קובץ לכל ספר-מקור
   baseline/snapshot_hashes.json        # hash-תוכן פר-ספר של ה-snapshot שנקשר לאחרונה
   meta.json                            # תגי-lineage אחרונים (ראו שלב 3)
   ```
2. סכמת רשומה בארטיפקט (שורה אחת ל-JSONL, קישור אחד). ‏`book_key` = **אובייקט מובנה
   התואם 1:1 ל-`BookKey` של ה-allocator** (‏`sourceName` + `canonicalHeTitle`) — לא מחרוזת
   דו-משמעית:
   ```json
   {"book_key": {"source_name":"MoreBooks", "canonical_he_title":"חזון איש"},
    "source_path":"MoreBooks/ספרים/אוצריא/.../חזון איש.txt",  // אופציונלי, ל-debug בלבד
    "line_index":28, "line_index_base":0,   // בסיס מוצהר במפורש (0-based)
    "start":37, "end":48,                    // offsets גולמיים (כולל HTML) לשורת-המקור
    "target_ref":"Psalms 16:8"}              // Ref.normal() אנגלי-קנוני — מפתח הפתרון
   ```
   - ‏`source_name` = "Sefaria" לספרי ספריא, או שם-מקור otzaria ("MoreBooks"/"DictaToOtzaria"/…).
     (בדוגמה: חזון איש הוא ספר MoreBooks → `source_name:"MoreBooks"`.)
   - שני ספרים באותה כותרת עברית ממקורות שונים **לא מתנגשים** — `source_name` מבדיל.
   - ה-`canonical_he_title` נגזר באותה נירמול שה-allocator מפעיל על `book.heRef`.
   - **דו-משמעי כבר סונן החוצה בשלב 2** — הארטיפקט מכיל רק חד-משמעיים.

**קבלה:** רפו קיים; קובץ-דוגמה נטען ומאומת סכמה (כולל `book_key` ייחודי); `.gitattributes`
עם דחיסה/LFS אם צריך.

---

## שלב 2 — מנוע הלינקר המקומי (הסטאק הכבד)

**מטרה:** פונקציה דטרמיניסטית: ספר-מקור (טקסט) → רשומות-ארטיפקט.

> ⚠️ **קריטי — מקור השורות (הכשל השקט הגדול):** ה-offsets וה-`line_index` תקפים
> **רק** אם הלינקר רץ על **בדיוק אותו תוכן-שורה** שהגנרטור יכניס ל-DB — כלומר
> **אחרי** הניקוי שלו (`cleanSefariaLine`, טיפול HTML, prefixים). הריצה הראשונה
> "עבדה" רק כי רצה על `line.content` של DB בנוי. **אסור להריץ על raw
> merged.json/`.txt`** — ה-offsets יסטו. **הפתרון:** משימת `dumpLines` ב-SeforimLibrary
> (שימוש-חוזר בקוד ה-import/ניקוי) שמפיקה `book_key → [line_content בסדר lineIndex]`;
> הלינקר צורך את ה-snapshot הזה. סדר: `dumpLines` → link → build Phase-2, כולם על
> אותו snapshot-מקור (lineage — שלב 3).

**צעדים:**
1. לארוז את הסביבה כ-image/סקריפט-הקמה חוזר: MongoDB + dump ספריא + gpu-server
   (+מודלים) + Sefaria-Project(Django). ‏cache ל-dump ולמודלים (ראו שלב 4).
2. סקריפט `link_books.py` (מבוסס על `run_worker.py` שכבר עובד):
   - קלט: ‏**`lines_snapshot` מ-`dumpLines`** — `(book_key, [שורות מנוקות])` בסדר lineIndex.
   - לכל שורה: `linker.bulk_link(..., type_filter='citation')`.
   - **מדיניות דו-משמעיות (מוכרעת):** `if rr.is_ambiguous: skip` (ברירת-מחדל).
     דגל `--bavli-convention` אופציונלי: אם כל המועמדים נבדלים רק ב-בבלי/ירושלמי
     או משנה/גמרא — לבחור בבלי/משנה; אחרת skip.
   - פלט: רשומות `{line_index,start,end,target_ref=rr.ref.normal()}`.
   - עמידות (מהריצה הקודמת): checkpoint לשורה, self-recycle לפי RSS, המתנת-NER.
3. **בלי כפל-resolver:** המנוע שומר `target_ref` בלבד. שום פתרון-ל-שורה כאן.

**קבלה:** הרצה על 5 ספרי-דגימה (מתוך `lines_snapshot`) מפיקה ארטיפקטים; 0 רשומות
דו-משמעיות; ‏`target_ref` תקין (עובר `Ref()`); ‏offsets מקיימים `raw[start:end]` = טקסט
הציטוט **על תוכן ה-snapshot** (לא על raw).

---

## שלב 3 — זיהוי-שינוי + דרייבר אינקרמנטלי (לב היעילות)

**מטרה:** להריץ את המנוע רק על ספרים שהשתנו, ולתחזק lineage — חסין לדילוגים.

> ⚠️ **קריטי — שעון-שינוי אחד בלבד: ה-snapshot.** הלינקר יכול לקשר **רק** את תוכן ה-snapshot,
> וחותם `source_hash` ממנו. לכן זיהוי-השינוי וה-baseline **חייבים** לעקוב אחרי ה-snapshot —
> אחרת שני השעונים מתפצלים: ספר שנקשר מול snapshot ישן נחתם עם תוכן שלא יתאים ל-DB של הבנייה,
> Phase-2 יזרוק אותו כ-stale, ואם ה-baseline התקדם לפי שעון **אחר** (manifests) — הספר לא יזוהה
> כמשתנה ולא יחזור ל-re-link לעולם. עקיבה אחרי ה-snapshot הופכת את הלולאה לקוהרנטית by-construction.

**צעדים:**
1. **זיהוי-שינוי מ-`lines_snapshot.db` (מקור-אמת יחיד):** לחשב hash-תוכן פר-ספר
   (‏`(source_name, canonical_he_title)`) על שורות ה-snapshot, ולדייף מול
   `baseline/snapshot_hashes.json`. `changed` = ספרים שה-hash שלהם השתנה/נוסף; `removed` =
   ספרים שב-baseline ונעלמו מה-snapshot. **לא manifests/titles** — הזהות נקראת ישירות מה-snapshot.
2. **rewrite של target_ref ל-renames של יעד (בלבד):** מעבר-מחרוזות זול על **כל** הארטיפקטים
   לפי `en_renamed` ב-`changelog_diff.json` — **בלי הרצת לינקר**. זה ה**שימוש היחיד** ב-changelog;
   הוא **לא** משמש לזיהוי-מקור. best-effort: rename שדולג במחזור → הפניה מתיישנת ונזרקת ב-build
   (‏safe-drop, לא מצביע-שגוי), ומתחדשת כשספר-המקור נקשר-מחדש.
3. **מחיקת ארטיפקטים ל-`removed`:** ספר שעזב את ה-snapshot (נמחק/שונה-שם) → מחיקת קובץ-הארטיפקט.
4. **הרצת המנוע (שלב 2) רק על `changed`** — מול **אותו** snapshot שממנו חושבו ה-hashים. הדרייבר
   מנקה את ה-ledger החולף (`done`/`claim`/`failed`) לפני כל invocation, כך שאין תלות בניקוי-חיצוני.
5. **קידום baseline + lineage — רק אחרי הרצת-engine מוצלחת** (כשל-תהליך מקפיץ חריגה לפני הקידום).
   ה-baseline נקבע ל-hashי ה-snapshot הנוכחי, **פרט לספרים שנכשלו פר-ספר** (‏`link_books.py` רושם
   אותם ב-ledger `failed/`): הם מוחזקים ב-hash-הקודם/נעדרים → מזוהים כ-`changed` ומנוסים במחזור הבא,
   בלי לחסום את השאר ובלי orphan. `meta.json` נושא sha256 של ה-snapshot + מספר-ספרים + export-tag(ספריא).
6. אריזת `artifacts/` ל-`linker_links.zst` ופרסום כ-release ברפו-הלינקר.

> הערה: הבאג הישן של regex שטוח-מול-מקונן ב-`SourceHashComputer.kt` (זיהוי-שינוי של הגנרטור
> **עצמו**) תוקן בשלב 0. הוא **לא** קשור עוד לזיהוי-המקור של הלינקר, שמבוסס כעת אך-ורק על ה-snapshot.

**קבלה:** בהרצה על שינוי מדומה של ספר בודד ב-snapshot — רק הוא עובר re-link; הרצה חוזרת על אותו
snapshot → 0; הרצת-engine שנכשלה **לא** מקדמת baseline (הספר עדיין מזוהה כמשתנה בהרצה הבאה — לא
ננטש); ספר שעזב את ה-snapshot → הארטיפקט שלו נמחק. (מכוסה ב-`test_incremental_e2e.py`.)

---

## שלב 4 — חיווט CI (טריגרים)

> **עדכון (2026-07-11) — חיווט סריאלי:** ה-relink כבר לא מופעל במקביל לבנייה אלא
> **מתוכה**: ‏manual-generate-release.yml מפיק snapshot, מפעיל את relink.yml עם
> ‏`library_run_id` וממתין לו, ואז מזריק את הארטיפקטים הטריים ל-DB (‏`-PlinkerStrict`).
> ‏weekly-pipeline.yml כבר לא מכיל job לינקר. הסעיפים למטה הם התכנון ההיסטורי.

**מטרה:** אוטומציה מקצה-לקצה, שני מזינים.

**צעדים:**
1. **‏SefariaExport:** להוסיף `changelog_diff.json` ל-`UPLOADS` ב-
   `22_generate_changelog.sh`; צעד סופי שמפעיל `repository_dispatch` לרפו-הלינקר (PAT).
2. **‏otzaria-library:** צעד שמפעיל `repository_dispatch` לרפו-הלינקר בשינוי ספרים.
3. **רפו-הלינקר:** workflow על `repository_dispatch` (משני המקורות) שמריץ את שלב 3
   על **self-hosted runner** (הסטאק כבד — Mongo+מודלים; לא runner קליל).

**קבלה:** ‏release ב-SefariaExport → הרפו-הלינקר מתעורר אוטומטית, מריץ, ומפרסם zst.

---

## שלב 5 — שילוב בגנרטור (SeforimLibrary)

**מטרה:** לפתור target_refs בבנייה ולכתוב קישורים לחיצים יציבי-delta.

**צעדים:**
1. **‏ConnectionType:** להוסיף `LINKER` ל-`core/.../models/Link.kt` + `fromString`.
2. **‏wiring קריאה:** להחריג `LINKER` ממנגנון מראת-SOURCE ב-`dao/.../LinkQueries.sq`;
   לעדכן DAO/אפליקציה שיטפלו בו כשכבה חד-כיוונית (לא COMMENTARY/SOURCE).
3. **‏sidecar (משמר את כל שדות RefEntry + מטביע `lineId`):** בשלב-ספריא
   (`SefariaDirectImporter.import()`), שם ה-`RefEntry` **וגם** `lineKeyToId((path,lineIndex)→lineId)`
   חיים בזיכרון, לשמור dump קומפקטי (SQLite/Protobuf): לכל RefEntry —
   **`(ref, heRef, path, lineIndex, lineId)`**. ⚠️ **לא להשמיט `path`/`lineIndex`** —
   ‏`resolveRefs` פועל על `RefEntry(ref,heRef,path,lineIndex)`, ובניית `refsByBase` בוחרת
   את הרשומה הראשונה לכל base **לפי `lineIndex`**. מוסיפים `lineId`, לא מחליפים.
4. **‏identity mapping (סוגר את הפער — ל-DB אין `path`/`book_key`):**
   - **‏DB עובדות:** `book`=(id,sourceId,title,heRef), `line`=(id,bookId,lineIndex) — **אין**
     עמודת path/book_key. לכן:
   - **יעד:** `resolveRefs(target_ref)` → `RefEntry` → **`RefEntry.lineId`** (מוטבע ב-sidecar) =
     `tgtLineId`. אין צורך ב-path→bookId.
   - **מקור:** בונים מפה `BookKey(sourceName, canonicalHeTitle) → bookId` **מה-DB** ע"י
     ‏`JOIN book.sourceId → source.name` (טבלת `source` נותנת id→name) + `canonicalHeTitle(book.heRef ?: book.title)`
     — אותה נירמול כמו ה-allocator (**לא** `getSourceNameFor(sourceId)` — הפונקציה מקבלת `Path`);
     ‏`book_key` בארטיפקט **חייב** להיות אותו `BookKey` בדיוק. ואז `(bookId, line_index)→lineId`
     מ-`SELECT bookId,lineIndex,id FROM line`.
5. **‏Phase-2 חדש `generateLinkerLinks`** (בסגנון `generateHavroutaLinks`):
   - מוריד `linker_links.zst`; **מאמת lineage** מול `meta.json` (כלל-הזהב).
   - טוען sidecar; בונה `refsByCanonical/refsByBase` עם **אותה** `canonicalCitation`; בונה
     ‏`BookKey→bookId` ו-`(bookId,lineIndex)→lineId` מה-DB (סעיף 4).
   - לכל רשומה: יעד ← `resolveRefs(target_ref).lineId`; מקור ← `BookKey→bookId`→`(bookId,line_index)→lineId`.
   - כותב `link(src→tgt, LINKER)` דרך `insertLinkStable` + `link_anchor(side=0, charStart/charEnd)`
     (‏offset גולמי→נראה דרך `countVisibleChars`); dedupe (`INSERT OR IGNORE`).
6. **‏delta:** אוטומטי — הכול עובר `IdAllocator`/`PatchDbProducer` הקיימים.

**קבלה:**
- **‏JVM test (סוגר את ממצא-האימות):** בונה `refsByCanonical/refsByBase` מ-sidecar
  ומריץ את `resolveRefs` **האמיתי** על מדגם של ≥500 target_refs מהארטיפקט → מאשש
  כיסוי ≈99% בפועל (לא proxy). זה הקריטריון שמאמת סופית את הנחת-היסוד.
- בנייה מפיקה קישורי `LINKER` עם עוגן לחיץ; פתיחה באפליקציה מראה קישור פנימי
  (לא `<a href>`); אפס דו-משמעיים ב-DB; ‏LINKER מוחרג ממראת-SOURCE.

---

## שלב 6 — Bootstrap מחדש על נתונים עדכניים

**מטרה:** לאכלס את הרפו על ה-export/otzaria הנוכחיים (v9 מיושן).

**צעדים:**
1. לוודא ששלבים 1–5 מוטמעים ובדוקים.
2. הרצת המנוע (שלב 2) על **כל** הקורפוס הנוכחי (ספריא + otzaria) → אכלוס
   `artifacts/` + `baseline/` + `meta.json`. (ריצה חד-פעמית ~שעות, כמו הקודמת.)
3. פרסום `linker_links.zst` ראשוני.
4. בניית DB מלאה (שלב 5) → אימות מדדים (ספרים, קישורים, עוגנים, דו-משמעי=0).

**קבלה:** DB עם ~1.8–1.9M קישורי `LINKER` לחיצים; lineage תואם ה-export שנבנה ממנו.

---

## שלב 7 — אימות delta (התרחיש הקריטי)

**מטרה:** להוכיח שעדכון-ספריא לא שובר, וש-re-link אינקרמנטלי = דקות.

**צעדים:**
1. שתי בניות עוקבות; בין השתיים — export חדש ששינה תוכן של כמה ספרי-**יעד**.
2. לוודא: (א) הלינקר לא הריץ NER על אותם ספרים (הם יעד, לא מקור); (ב) הקישורים
   שורדים דרך פתרון-מחדש; (ג) `PatchDbProducer` מפיק patch **קטן**; (ד)
   `verifyApplyChain` עובר.
3. תרחיש שני: שינוי ספר-**מקור** אחד → רק הוא עובר re-link; ה-patch מוגבל אליו.

**קבלה:** ‏patch של קישורים פרופורציונלי לשינוי בלבד; אפס שבירה שקטה; זמן
אינקרמנטלי = דקות.

---

## סיכום זרימה (מקצה לקצה)

```
export/otzaria משתנה → repository_dispatch → רפו-לינקר:
  דיף-מול-בסיס-עצמי → re-link רק שינויים → rewrite refs ל-renames →
  zst(+lineage) → release
                              ↓
SeforimLibrary build → Phase-2: verify lineage → resolveRefs(target_ref) →
  link(src→tgt,LINKER)+link_anchor(side0) → IdAllocator/Patch (delta אוטומטי)
```

**מדדי-יעילות מובנים:** re-link רק לספרים שהשתנו (דקות); פתרון-יעד בבנייה
(שניות, ב-memory); ‏delta אוטומטי; שני מזינים, בסיס-עצמי חסין-דילוגים.

</div>
