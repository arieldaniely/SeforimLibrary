#!/usr/bin/env bash
#
# מזרים שינויים מ-upstream/master לאורך שרשרת הענפים ודוחף ל-origin.
#
# המבנה הוא HYBRID:
#   • שכבת בסיס+פיצ'רים = stack לינארי ב-REBASE. כל ענף יושב על גב קודמו, כך
#     שכל PR ל-upstream מכיל רק את הקומיטים שלו מעל הקודם. (master ... feat/hearot)
#   • otzaria = נבנה מחדש בכל ריצה כ-MERGE-BASED עם בועות נקיות: מ-default_commentators,
#     לכל פיצ'ר נבנית בועה (ה-delta שלו על ה-mainline) שממוזגת ב---no-ff, כך שכל
#     ענף נפתח ונסגר בגרף (|\ ... |/) כמו merge של PR. מעל הכל מונחים הקומיטים
#     הספציפיים לאוצריא (blacklist/תא שמע/ביטולים/manifest).
#   • linker = בועה אחרונה בראש otzaria. חריג: אינו פיצ'ר טהור מעל default_commentators
#     אלא מבוסס על שכבת-התשתית של אוצריא (זקוק ל-workflow הרליס + הורדת ספריית-אוצריא),
#     ולכן 3 הקומיטים שלו מלוקטים לבועה מעל קומיטי-אוצריא (לא בין בועות-הפיצ'ר) וממוזגים
#     ב---no-ff. otzaria נשארת הענף העליון (ראשה = ה-merge).
#
# למה merge ל-otzaria ו-rebase לפיצ'רים? כדי שבהיסטוריה של otzaria יהיה ברור
# איפה כל ענף מתחיל ונגמר (bubbles + הודעות Merge), ובו-זמנית כל ענף-PR יישאר
# לינארי ונקי מול upstream.
#
# בקונפליקט הסקריפט עוצר ומדפיס פקודת שחזור. עבודה מקבילה לא תידרס (force-with-lease).
#
# שימוש:  scripts/cascade-master.sh            # מריץ, מגבה, ודוחף
#         DRY_RUN=1 scripts/cascade-master.sh  # בלי push
set -euo pipefail

# ── שכבת הבסיס+פיצ'רים: stack לינארי (rebase), מהבסיס (= upstream) ולמעלה ─────
#   master                     = upstream/master בדיוק (ff-only, בלי קומיטים משלו)
#   metadata_A/B               = seedAllMetadata + תיאורים (Otzaria PR)
#   default_commentators       = מפרשי ברירת-מחדל (Otzaria PR #10)
#   fix/category-ids-full-path = מפתוח קטגוריות לפי נתיב מלא
#   fix/book-corpus-talmud     = תיקון _book_corpus לתלמוד בבלי/ירושלמי
#   perf/faster-generation     = TOC ב-batch + ריצת tmpfs
#   word-level-link-anchors    = עוגני-מילה לקישורים (טבלת link_anchor)
#   feat/ranged-links-and-book-versions = קישורי-טווח + גרסאות ספרים + black_versions
#   fix/source-numbered-prefix = דיכוי prefix כפול בספרים ממוספרים-במקור
#   feat/hearot-standalone-books = ספרי "הערות" עצמאיים כמפרשי ברירת-מחדל
#   feat/otzaria-ranged-links-and-alt-toc = קישורי-טווח + alt-toc + תיקון SOURCE הפוך (otzariasqlite)
STACK=(
  master
  metadata_A
  metadata_B
  default_commentators
  fix/category-ids-full-path
  fix/book-corpus-talmud
  perf/faster-generation
  word-level-link-anchors
  feat/ranged-links-and-book-versions
  fix/source-numbered-prefix
  feat/hearot-standalone-books
  feat/otzaria-ranged-links-and-alt-toc
)

# ── otzaria: merge-based (מעל שכבת הפיצ'רים) ──────────────────────────────────
FEATURE_BASE=default_commentators
FEATURES=(
  fix/category-ids-full-path
  fix/book-corpus-talmud
  perf/faster-generation
  word-level-link-anchors
  feat/ranged-links-and-book-versions
  fix/source-numbered-prefix
  feat/hearot-standalone-books
  feat/otzaria-ranged-links-and-alt-toc
)
TOP=otzaria
TOP_PARENT=feat/otzaria-ranged-links-and-alt-toc   # קומיטי-אוצריא הם ה-non-merge שמעל ענף זה
LINKER=linker   # בועת-לינקר בראש otzaria (3 קומיטים מעל תשתית-אוצריא; לא פיצ'ר טהור)

UPSTREAM_REMOTE=upstream
UPSTREAM_BRANCH=master
PUSH_REMOTE=origin
BAK_NS=refs/cascade-bak   # מרחב גיבוי; שחזור: git branch -f <branch> <BAK_NS>/<branch>

# 0. עץ עבודה נקי (קבצים לא-במעקב לא מפריעים, לכן --untracked-files=no)
if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  echo "✗ עץ העבודה אינו נקי (שינויים במעקב). בצע commit/stash לפני ה-cascade." >&2
  exit 1
fi

# 1. תיעוד ה-tip הישן של כל ענף — לפני כל שינוי (גבול ל-rebase --onto + גיבוי).
#    bash 3.2 (macOS) — בלי assoc arrays; מערך מקבילי ל-STACK.
OLD=()
for br in "${STACK[@]}"; do OLD+=("$(git rev-parse "refs/heads/$br")"); done
OTZARIA_OLD="$(git rev-parse "refs/heads/$TOP")"

# 2a. בועת-הלינקר: אתר את ה-merge העליון של linker (גם אם CI הוסיף manifest מעליו).
#     3 הקומיטים שלה = base..linker-tip; נחריג אותם מ-OTZARIA_ONLY וניצור להם בועה משלהם.
LINKER_MERGE="$(git rev-list --merges --grep="Merge branch '$LINKER'" -1 "$TOP" || true)"
LINKER_ONLY=()
if [[ -n "$LINKER_MERGE" ]]; then
  while read -r sha; do [[ -n "$sha" ]] && LINKER_ONLY+=("$sha"); done \
    < <(git rev-list --reverse --no-merges "${LINKER_MERGE}^1..${LINKER_MERGE}^2")
fi
LINKER_SET=" ${LINKER_ONLY[*]} "   # לחיפוש חברות בלולאת OTZARIA_ONLY (bash 3.2 — בלי assoc)
echo "==> ${#LINKER_ONLY[@]} קומיטי-לינקר יונחו כבועה בראש"

# 2b. קומיטי-אוצריא: ה-non-merge שמעל TOP_PARENT, ללא קומיטי manifest (נבנים מחדש)
#     וללא קומיטי-הלינקר (בועה נפרדת). נלכד עכשיו מה-tip הישן, לפני ה-rebase שיזיז הכל.
OTZARIA_ONLY=()
while read -r sha; do
  [[ -z "$sha" ]] && continue
  case "$LINKER_SET" in *" $sha "*) continue;; esac   # קומיטי-לינקר → בועה נפרדת
  subj="$(git log -1 --format=%s "$sha")"
  case "$subj" in
    "Update release manifest"*|chore*manifest*|chore*release-manifest*) continue;;
  esac
  OTZARIA_ONLY+=("$sha")
done < <(git rev-list --reverse --no-merges "$TOP_PARENT".."$TOP")
echo "==> ${#OTZARIA_ONLY[@]} קומיטי-אוצריא יונחו מעל המיזוגים"

# 3. גיבוי כל הענפים תחת refs/cascade-bak/ (נדרס בכל ריצה)
echo "==> מגבה את כל הענפים תחת $BAK_NS/"
for i in "${!STACK[@]}"; do git update-ref "$BAK_NS/${STACK[i]}" "${OLD[i]}"; done
git update-ref "$BAK_NS/$TOP" "$OTZARIA_OLD"
git rev-parse -q --verify "refs/heads/$LINKER" >/dev/null && git update-ref "$BAK_NS/$LINKER" "$(git rev-parse "refs/heads/$LINKER")"

# 4. master = upstream/master בדיוק (fast-forward בלבד)
echo "==> מביא מ-$UPSTREAM_REMOTE ומעדכן את $UPSTREAM_BRANCH"
git fetch "$UPSTREAM_REMOTE"
git switch "$UPSTREAM_BRANCH"
git merge --ff-only "$UPSTREAM_REMOTE/$UPSTREAM_BRANCH"

# 5. rebase לינארי של שכבת הבסיס+פיצ'רים: כל ענף על גב קודמו.
#    "git rebase --onto <prev> <prev_old>" משחזר רק את הקומיטים של הענף עצמו
#    (הטווח prev_old..cur) על ה-tip החדש של הקודם.
restore_hint() {
  echo "  לשחזור מלא:  for b in ${STACK[*]} $LINKER $TOP; do git branch -f \"\$b\" \"$BAK_NS/\$b\"; done" >&2
}
for ((i = 1; i < ${#STACK[@]}; i++)); do
  prev="${STACK[i-1]}"; cur="${STACK[i]}"; prev_old="${OLD[i-1]}"
  prev_new="$(git rev-parse "refs/heads/$prev")"
  if [[ "$prev_new" == "$prev_old" ]] && git merge-base --is-ancestor "$prev_new" "refs/heads/$cur"; then
    echo "==> $cur ללא שינוי ($prev לא זז)"; continue
  fi
  echo "==> rebase $cur על גב $prev (--onto $prev ${prev_old:0:7})"
  git switch "$cur"
  if ! git rebase --onto "$prev" "$prev_old"; then
    echo "✗ קונפליקט ב-rebase של $cur — מבצע rebase --abort ועוצר." >&2
    git rebase --abort || true; restore_hint; exit 2
  fi
done

# 6. בניית otzaria מחדש: default_commentators → בועה נקייה לכל פיצ'ר → קומיטי-אוצריא.
#    לכל פיצ'ר בונים ענף ארעי מ-tip ה-otzaria הנוכחי, מלקטים לתוכו רק את ה-delta
#    של הפיצ'ר (prev..f), וממזגים ב---no-ff. כך כל בועה נפתחת מה-mainline ונסגרת
#    בחזרה (|\ ... |/) — כמו merge של PR — במקום מסילה מקבילה רציפה. הודעת ה-merge
#    נכפית לשם הפיצ'ר האמיתי. ענפי הפיצ'ר עצמם לא נוגעים (נשארים טהורים ל-PR);
#    הבועות ב-otzaria הן עותקי-delta (SHA חדשים), כמו "rebase & merge".
echo "==> בונה מחדש את $TOP (merge-based, בועות נקיות) מעל $FEATURE_BASE"
git switch "$TOP"
git reset --hard "$FEATURE_BASE"
for j in "${!FEATURES[@]}"; do
  f="${FEATURES[j]}"
  if [[ $j -eq 0 ]]; then base="$FEATURE_BASE"; else base="${FEATURES[j-1]}"; fi
  echo "==>   בועה: Merge $f into $TOP (delta $base..$f)"
  git branch -D _bub 2>/dev/null || true
  git switch -c _bub "$TOP" >/dev/null
  if ! git cherry-pick "$base..$f"; then
    echo "✗ קונפליקט ב-cherry-pick של $f — abort ועוצר." >&2
    git cherry-pick --abort || true; git switch "$TOP"; git branch -D _bub; restore_hint; exit 3
  fi
  git switch "$TOP" >/dev/null
  if ! git merge --no-ff -m "Merge branch '$f' into $TOP" _bub; then
    echo "✗ קונפליקט ב-merge של בועת $f — merge --abort ועוצר." >&2
    git merge --abort || true; git branch -D _bub; restore_hint; exit 3
  fi
  git branch -D _bub >/dev/null
done
for sha in "${OTZARIA_ONLY[@]}"; do
  if ! git cherry-pick "$sha"; then
    echo "✗ קונפליקט ב-cherry-pick של קומיט-אוצריא $sha — abort ועוצר." >&2
    git cherry-pick --abort || true; restore_hint; exit 4
  fi
done
# שימור release-manifest.json למצב הקודם (CI מייצר אותו מחדש; מונע diff מפתיע).
if git cat-file -e "$OTZARIA_OLD:release-manifest.json" 2>/dev/null; then
  git checkout "$OTZARIA_OLD" -- release-manifest.json
  git commit -q -m "chore(otzaria): שימור release-manifest.json למצב החי" 2>/dev/null || true
fi

# 6b. בועת-הלינקר בראש: 3 הקומיטים מלוקטים על תשתית-אוצריא (זמינה כעת ב-tip) וממוזגים
#     ב---no-ff. ענף linker מקודם לקומיטים המשוחזרים כדי שיישאר תואם. otzaria נשארת עליונה.
if [[ ${#LINKER_ONLY[@]} -gt 0 ]]; then
  echo "==>   בועה: Merge $LINKER into $TOP (${#LINKER_ONLY[@]} קומיטים, מעל תשתית-אוצריא)"
  git branch -D _bub 2>/dev/null || true
  git switch -c _bub "$TOP" >/dev/null
  for sha in "${LINKER_ONLY[@]}"; do
    if ! git cherry-pick "$sha"; then
      echo "✗ קונפליקט ב-cherry-pick של קומיט-לינקר $sha — abort ועוצר." >&2
      git cherry-pick --abort || true; git switch "$TOP"; git branch -D _bub; restore_hint; exit 5
    fi
  done
  git branch -f "$LINKER" _bub          # ענף linker = הקומיטים המשוחזרים (על ה-tip החדש)
  git switch "$TOP" >/dev/null
  if ! git merge --no-ff -m "Merge branch '$LINKER' into $TOP" _bub; then
    echo "✗ קונפליקט ב-merge של בועת $LINKER — merge --abort ועוצר." >&2
    git merge --abort || true; git branch -D _bub; restore_hint; exit 5
  fi
  git branch -D _bub >/dev/null
fi

# 7. דחיפה — force-with-lease נדחה אוטומטית אם ה-remote זז מתחת לרגליים.
if [[ "${DRY_RUN:-0}" == "1" ]]; then
  echo "==> DRY_RUN: מדלג על push. הענפים: ${STACK[*]} $LINKER $TOP"
else
  echo "==> דוחף את כל הענפים ל-$PUSH_REMOTE (force-with-lease)"
  for br in "${STACK[@]}" "$LINKER" "$TOP"; do
    git push --force-with-lease "$PUSH_REMOTE" "refs/heads/$br:refs/heads/$br"
  done
fi

echo "✓ ה-cascade הושלם (בסיס+פיצ'רים ב-rebase, otzaria ב-merge)."
