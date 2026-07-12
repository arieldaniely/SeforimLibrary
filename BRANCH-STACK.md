# שרשרת הענפים — מ־`master` ועד `otzaria`

מסמך זה מתעד את ארכיטקטורת הענפים של הפורק. ההזרמה והדחיפה מבוצעות ע"י
[`scripts/cascade-master.sh`](scripts/cascade-master.sh).

## המבנה: היברידי (rebase לבסיס+פיצ'רים, merge-בועות ל-otzaria)

- **שכבת בסיס+פיצ'רים = stack לינארי ב-REBASE.** כל ענף יושב על גב קודמו, כך שכל
  PR ל-upstream מכיל רק את הקומיטים של אותו ענף מעל הקודם. (`master` … `feat/otzaria-ranged-links-and-alt-toc`)
- **`otzaria` = merge-based עם בועות נקיות.** נבנה מחדש בכל cascade: מ-`default_commentators`,
  לכל פיצ'ר נפתחת **בועה** (ה-delta שלו) שממוזגת ב-`--no-ff`, ומעליהן מונחים הקומיטים
  הספציפיים לאוצריא. כך בהיסטוריה של `otzaria` **רואים בבירור איפה כל ענף מתחיל ונגמר**:

```
*   Merge branch 'linker' into otzaria          ← בועת הלינקר (ראש otzaria)
|\
| * Stage 5: LINKER connection type + sidecar + Phase-2 generateLinkerLinks
| * Build: publish lines_snapshot.db.zst for the linker
| * Linker stage 0: fix Otzaria source-hash detection + add dumpLines
|/
*   … קומיטי-אוצריא (blacklist / תא שמע / manifest) …
*   Merge branch 'feat/otzaria-ranged-links-and-alt-toc' into otzaria
|\
| * fix(otzaria): שמירת קישורי SOURCE בכיוון קנוני (בסיס→מפרש)
| * feat(otzaria): ranged links + alt-toc structures in the otzariasqlite generator
|/
*   Merge branch 'feat/hearot-standalone-books' into otzaria
|\
| * feat(otzaria): import 'הערות' companion files…
|/
*   Merge branch 'fix/source-numbered-prefix' into otzaria
|\
| * fix(sefariasqlite): מניעת כפל אותיות סעיף-קטן בספרים ממוספרים-במקור
|/
*   Merge branch 'feat/ranged-links-and-book-versions' into otzaria
|\
| * feat(sefariasqlite): רשימה שחורה לגרסאות ספרים (black_versions.txt)
| * fix(versions): flush batches per version file
| * feat(versions): per-edition text storage
| * feat(links): multi-line ranged link support
|/
*   Merge branch 'word-level-link-anchors' into otzaria
|\  …
```

> **הבחנה חשובה:** הבועות ב-`otzaria` הן **עותקי-delta** של הפיצ'רים (SHA חדשים,
> כמו "rebase & merge" בגיטהאב). ענפי הפיצ'ר עצמם (`fix/*`, `perf`, `word-level`,
> `ranged`, `hearot`, `otzaria-ranged`) נשארים **טהורים ולינאריים** — מוכנים ל-PR ל-upstream.
>
> **חריג — `linker`:** בניגוד ליתר הענפים, `linker` **אינו** פיצ'ר טהור מעל
> `default_commentators`. הוא מבוסס על **שכבת-התשתית של אוצריא** (זקוק ל-workflow
> הרליס ולהורדת ספריית-אוצריא), ולכן הבועה שלו יושבת **בראש** `otzaria` — מעל
> קומיטי-אוצריא — ולא בין שאר בועות-הפיצ'ר. `otzaria` נשארת הענף העליון (ראשה = ה-merge).

---

## תרשים השרשרת

```
upstream/master
   │  (ff-only, זהה בדיוק)
   ▼
master → metadata_A → metadata_B → default_commentators        [בסיס — rebase]
                                          │
   ┌──────────────────────────────────────┘
   ▼
fix/category-ids-full-path → fix/book-corpus-talmud → perf/faster-generation
   → word-level-link-anchors → feat/ranged-links-and-book-versions
   → fix/source-numbered-prefix → feat/hearot-standalone-books
   → feat/otzaria-ranged-links-and-alt-toc
                                                                [פיצ'רים — rebase, PR נפרד לכל אחד]
                                          │
                                          ▼   (כל פיצ'ר → בועת merge)
                                       otzaria  = בועות הפיצ'רים + קומיטי-אוצריא
                                          │                + בועת linker בראש
                                          ▼
                                       linker   (7 קומיטים מעל תשתית-אוצריא) ──┐
                                          └───────────── merge --no-ff ────────┘
```

## טבלת סיכום

| # | ענף | מעל | קומיטים | ייעוד |
|---|-----|-----|:---:|-------|
| 1 | `master` | `upstream/master` | 0 | מראה מדויק של upstream |
| 2 | `metadata_A` | `master` | 1 | seedAllMetadata (Otzaria PR #84) |
| 3 | `metadata_B` | `metadata_A` | 5 | seedAllMetadata — תיאורים/מו"ל + תיקון דריסת-מקור |
| 4 | `default_commentators` | `metadata_B` | 7 | מפרשי ברירת-מחדל (Otzaria PR #10) |
| 5 | `fix/category-ids-full-path` | `default_commentators` | 1 | תיקון מזהי קטגוריה |
| 6 | `fix/book-corpus-talmud` | ↑ | 1 | תיקון `_book_corpus` לתלמוד |
| 7 | `perf/faster-generation` | ↑ | 2 | TOC ב-batch + ריצת tmpfs |
| 8 | `word-level-link-anchors` | ↑ | 3 | עוגני-מילה לקישורים |
| 9 | `feat/ranged-links-and-book-versions` | ↑ | 4 | קישורי-טווח + גרסאות ספרים + black_versions |
| 10 | `fix/source-numbered-prefix` | ↑ | 1 | דיכוי prefix כפול בספרים ממוספרים-במקור |
| 11 | `feat/hearot-standalone-books` | ↑ | 2 | ספרי "הערות" עצמאיים כמפרשים + תיקון חברותא (מזהים יציבים) |
| 12 | `feat/otzaria-ranged-links-and-alt-toc` | ↑ | 2 | קישורי-טווח + alt-toc + תיקון SOURCE הפוך |
| — | `otzaria` | (merge של כולם) | 12 (+manifest) | קומיטים ספציפיים לאוצריא |
| 13 | `linker` | תשתית-אוצריא (בראש `otzaria`) | 7 | לינקר: DumpLines + source-hash + Phase-2 + הקשחה + קישורי-טווח |

---

## פירוט הענפים

### 1. `master`
מראה מדויק (`ff-only`) של `upstream/master` (kdroidFilter/SeforimLibrary). בלי קומיטים
משלו — נקודת העוגן של כל השרשרת. עיקרון: לצמצם סטייה מ-upstream.

### 2–3. `metadata_A` / `metadata_B`
`seedAllMetadata` — post-process שמזרים תיאורים, נתוני מו"ל ומקור לספרים:
`feat: add seedAllMetadata…`, `fix gemini`, `close connection, skip null`,
`fix: multiline CSV records and explicit Sourcefolder mapping`. base ל-Otzaria PR.
**כולל את התיקון `מקור-הספר לא נדרס משכבת המטא-דאטה`** — הוסרה דריסת ה-sourceId
לפי Sourcefolder (הבאג נוצר כאן; המקור קנוני מ-`files_manifest` בייבוא).

### 4. `default_commentators`
מפרשי ברירת-המחדל לספרים (Otzaria PR #10): פיצול משנה ברורה לספר עצמאי, פערים
מכוונים במיקומי מפרשים, נרמול שמות ב-alt-toc, ו-refactor ל-`DefaultCommentatorPosition`.

### 5. `fix/category-ids-full-path`
תיקון: מזהי קטגוריה לפי נתיב מלא מהשורש (לא לפי שם-עלה), למניעת התנגשות מזהים
(שו"ת שנחתו תחת קבלה/מחברי זמננו). — `fix(otzaria): key category ids by full path`

### 6. `fix/book-corpus-talmud`
עדכון לוגיקת `_book_corpus` לכלול "Talmud" עבור בבלי וירושלמי (+טסט).

### 7. `perf/faster-generation`
שיפורי מהירות: הכנסת רשומות TOC ב-batch בטרנזקציה אחת; הגדרת ריצה ל-tmpfs.

### 8. `word-level-link-anchors`
עוגני-מילה לקישורים — טבלת `link_anchor` לעיגון קישור לטווח תווים בשורה:
word-level anchors, ייבוא charLevelData מדויק, ותוויות תצוגה לעוגני order-only.

### 9. `feat/ranged-links-and-book-versions`
- `feat(links)`: קישורי-טווח החוצים שורות ("Exodus 1:1-6:1") — `link_range`/`link_coverage`.
- `feat(versions)`: אחסון גרסאות/מהדורות ספר — `book_version`/`version_line`.
- `fix(versions)`: flush ב-batch לכל קובץ-גרסה.
- `feat(sefariasqlite)`: **`black_versions.txt`** — רשימה שחורה לגרסאות ספרים (זבל +
  מהדורות בשפה זרה); גרסה חסומה לא נכנסת ל-DB כלל.
> תומכי delta (רשומים ב-`PatchTables` וב-`LogicalContentHasher`).

### 10. `fix/source-numbered-prefix`
תיקון sefariasqlite: ספריא הטמיעה בהדרגה את אותיות הסעיפים בתוך הטקסט, והטלאי שהוסיף
`(א) ` גרם לכפל `(א) (א)` (משנה ברורה ועוד ~12 ספרים). `sourceMarkerRun` מזהה מערך-עלה
ממוספר-במקור (גוש רציף של אותיות עוקבות) ומדכא את הקידומת רק שם. — סופק-עצמאי, מצומד
לוגית לעוגני שער-הציון שב-`word-level-link-anchors`. — `fix(sefariasqlite): מניעת כפל אותיות סעיף-קטן`

### 11. `feat/hearot-standalone-books`
קבצי "הערות על &lt;title&gt;" מיובאים כספרים עצמאיים מקושרים (במקום `notesContent`
הישן שאף לקוח לא הציג), ומוגדרים כמפרשי ברירת-מחדל לפי טבלת הקישורים (לא לפי תחיליות
שם). — `feat(otzaria): import 'הערות' companion files as standalone linked commentator books`
- **תיקון חברותא (`GenerateHavroutaLinks.kt`):** מזהי-מקצה יציבים לקישורים טרנזיטיביים
  (יציבות ל-delta) ומחיקה ממוקדת-סוג. — `Havrouta: stable allocator ids for transitive
  links + type-scoped delete`. (`GenerateHavroutaLinks.kt` הוא קובץ-בסיס/upstream; התיקון
  שוכן כאן כי זהו הפיצ'ר שמרחיב את מערכת החברותא — קישורי Talmud-Hearot.)

### 12. `feat/otzaria-ranged-links-and-alt-toc`
מביא את הגנרטור של **otzariasqlite** לרמת ה-importer של ספריא בשלושה היבטים
שהיו עד כה בצד ספריא בלבד:
- **קישורי-טווח:** ה-JSON יכול לשאת `line_index_1_end`/`line_index_2_end`
  (שורת-סיום 1-based לכל צד); הקישור נשאר מעוגן בשורת ההתחלה, `link_range`
  שומר את שורת הסוף ו-`link_coverage` מסמן כל שורה מכוסה (כותרות מוחרגות).
  הקישורים נכנסים דרך `insertLinkStable` לפי `(source,target,connectionType)`,
  וטווחים ישנים נמחקים לפני ייבוא-מחדש (סנכרון סמכותי, delta-friendly).
- **מבני alt-toc ("עליות"):** קריאת `alt_toc/<book>_alt_toc.json` (המקבילה
  האוצריאית ל-`alts` של ספריא) → `alt_toc_structure`/`alt_toc_entry`/
  `line_alt_toc`, כולל מיפוי כל שורה לעוגן הקודם הקרוב וסימון ילד-אחרון/יש-ילדים.
  ספרי-ספריא לא נגעים (המבנים שלהם מהסכימה); מבנים/מפתחות שאינם מוכרזים עוד נמחקים.
- **תיקון קישורי SOURCE הפוכים:** קישור שהוצהר `SOURCE` (מקובץ הצד-התלוי, למשל מפרש
  רמב"ם→משנה תורה) נשמר כעת בכיוון קנוני base→dependant כ-`COMMENTARY`, כמו ב-importer
  של ספריא (מתקן מפרשים שהוצגו הפוך, Otzaria/otzaria#531). ה-flip מהפך גם את צדדי
  ה-range ומדלג על עוגן-המקור.
> תלוי בטבלאות `link_range`/`link_coverage` (פיצ'ר #9) — לכן ממוקם מעליו.
> טסטים: `OtzariaRangedLinksTest`, `OtzariaAltTocTest`, `OtzariaSourceLinksTest`.

### `otzaria` — ראש השרשרת
הענף הראשי של הפורק. מכיל את **בועות** כל הפיצ'רים (merge לכל אחד), ומעליהן **רק**
את הקומיטים הספציפיים לאוצריא — רבים מהם "ביטולים" של שלבי ריצה, או סטיות מכוונות
והפיכות מ-upstream: `התאמה לאוצריא`, `delta-updater לא סביב Lucene`, `ביטול catalog.pb`,
`ביטול bundle`, `otzaria כראשי`, `manifest כשאין releases`, `הורדת ספריית אוצריא בלבד`,
`אי-אריזת מודל ההטמעה`, `שינויי מיקומים → תת-קטגוריה`, החרגת/התרת "תא שמע", ועריכת
`books_blacklist`. מעל קומיטי-אוצריא — קומיטי manifest של ה-CI, ובראש הכל **בועת `linker`**.

### 13. `linker` — בועה בראש `otzaria`
תמיכת ה-**לינקר** (Sefaria linker → קישורי-LINKER ב-DB). שבעה קומיטים המבוססים על
**שכבת-התשתית של אוצריא** (ולכן אינם פיצ'ר טהור — ראו החריג למעלה), ממוזגים ב-`--no-ff`
בראש `otzaria` (3 בסיס + 4 תיקונים):
- **`Linker stage 0`** — זיהוי source-hash של אוצריא ב-`SourceHashComputer` (מעקב שינויי-מקור
  לצורך snapshot), ו-`DumpLines` (packaging) לייצוא תוכן-שורות נקי שהלינקר החיצוני צורך.
  נוגע גם ב-`Generator.kt` (otzariasqlite) — ולכן ממוקם מעל בועת `feat/otzaria-ranged…`.
- **`Build: publish lines_snapshot.db.zst`** — הרחבת `manual-generate-release.yml`: dumpLines
  על ה-DB הבנוי, דחיסה ל-`lines_snapshot.db.zst` כ-release asset, ושלב Phase-2 שמושך
  `linker_links.zst` מהריצה הקודמת וממיר אותו לקישורי-LINKER. **תלוי בצינור-הרליס של
  אוצריא** (catalog.pb / patch-fan / `Otzaria/LinkerToOtzaria`) — לכן חייב בסיס-אוצריא.
- **`Stage 5: LINKER connection type + sidecar + Phase-2 generateLinkerLinks`** — סוג-קישור
  `LINKER` ב-`Link.kt`, sidecar של `RefEntry→lineId` ב-`SefariaDirectImporter`,
  ו-`GenerateLinkerLinks` (sefariasqlite) שמייצר את הקישורים מ-artifacts של הלינקר.

ארבעה תיקונים (12/07/2026):
- **`Phase-2 hardening`** — מטמון-תוכן חסום, מגן התנגשות-מזהים, ו-`-PlinkerStrict`
  (`GenerateLinkerLinks` + `InMemoryIdAllocator`).
- **`Otzaria import: never ingest legacy "linker"-typed link rows`** — הגנרטור של אוצריא
  מדלג על שורות-קישור מסוג "linker" ישן (`Generator.kt`).
- **`Serial linker cycle: the build runs relink mid-pipeline`** — מחזור-לינקר סריאלי
  שרץ באמצע ה-pipeline (`manual-generate-release.yml` + מסמכי הלינקר).
- **`Phase-2: target ranges for multi-line citations`** — טווחי-יעד לציטוטים רב-שורתיים
  (`link_range` צד=1); `GenerateLinkerLinks` + `SefariaImportRefs`.
> טסטים: `SourceHashComputerTest`, `GenerateLinkerLinksTest`, `InMemoryIdAllocatorTest`.
> תיעוד: `LINKER_DELTA_PLAN.md`, `LINKER_IMPLEMENTATION_STAGES.md`.

---

## הוספת פיצ'ר חדש

1. בסס ענף חדש על ראש שכבת הפיצ'רים (מתחת ל-`otzaria`), עם הקומיטים שלו בלבד.
2. הוסף שורה ב-`STACK` וב-`FEATURES` שב-[`scripts/cascade-master.sh`](scripts/cascade-master.sh),
   במקום הנכון (הוא יבוסס על הקודם).
3. הרץ את ה-cascade: הוא יבצע rebase לינארי לשכבת הפיצ'רים, ויבנה מחדש את `otzaria`
   עם בועה חדשה לפיצ'ר + קומיטי-אוצריא מעל. `DRY_RUN=1` לבדיקה בלי push.

> **כלל:** קומיטי `otzaria` תמיד בראש; PR של פיצ'ר ממוזג **מתחת** להם (כבועה).
