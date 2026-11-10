#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO="k69437254-creator/Tvshere"
BRANCH="rc1-validation"
cd "$ROOT"

fail() { printf '\n[Tvshere] ERROR: %s\n' "$*" >&2; exit 1; }
info() { printf '[Tvshere] %s\n' "$*"; }

command -v git >/dev/null 2>&1 || fail "Git غير مثبت. نفّذ: pkg install git"
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "فك ضغط ملفات الإصدار داخل مجلد استنساخ Tvshere الذي يحتوي مجلد .git."

REMOTE_URL="$(git remote get-url origin 2>/dev/null || true)"
[[ -n "$REMOTE_URL" ]] || fail "لم أجد remote باسم origin. أضف مستودع GitHub الصحيح أولاً."
case "$REMOTE_URL" in
  *github.com:k69437254-creator/Tvshere.git|*github.com/k69437254-creator/Tvshere.git|*github.com/k69437254-creator/Tvshere)
    ;;
  *) fail "المستودع origin ليس $REPO (القيمة الحالية: $REMOTE_URL). لم يحدث أي تغيير على Git." ;;
esac

[[ -f app/build.gradle.kts ]] || fail "ملفات المصدر غير موجودة في مجلد العمل. فك ضغط حزمة الإصدار هنا أولاً."
[[ -f app/src/main/java/com/hassouni/tv/stream/BroadcastController.kt ]] || fail "BroadcastController غير موجود؛ هذه ليست حزمة الإصدار الكاملة."
[[ -f app/src/main/java/com/hassouni/tv/capture/UvcVideoSource.kt ]] || fail "مصدر UVC المدمج غير موجود."
[[ -f app/src/main/java/com/hassouni/tv/audio/UsbAudioSource.kt ]] || fail "مصدر الصوت USB غير موجود."

grep -q 'RootEncoder:library:2.8.1' app/build.gradle.kts || fail "اعتمادية RootEncoder غير مثبتة في المصدر."

CURRENT_BRANCH="$(git branch --show-current 2>/dev/null || true)"
if [[ -z "$CURRENT_BRANCH" ]]; then
  git checkout -b "$BRANCH"
  CURRENT_BRANCH="$BRANCH"
fi
if [[ "$CURRENT_BRANCH" != "$BRANCH" ]]; then
  info "الفرع الحالي $CURRENT_BRANCH؛ التحويل إلى $BRANCH."
  git checkout "$BRANCH" 2>/dev/null || git checkout -b "$BRANCH"
fi

info "تشغيل فحص بنية الإصدار…"
bash scripts/verify_release.sh

git diff --check || fail "وجدت مسافات/أخطاء whitespace في التعديلات. لم يتم الرفع."

if [[ "${TVSHERE_BUILD_LOCAL:-0}" == "1" ]]; then
  if [[ -x ./gradlew ]]; then
    info "تنفيذ بناء محلي عبر Gradle Wrapper…"
    ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
  elif command -v gradle >/dev/null 2>&1; then
    info "تنفيذ بناء محلي عبر Gradle المثبت…"
    gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon
  else
    fail "طلبت TVSHERE_BUILD_LOCAL=1 لكن Gradle غير مثبت. أزل المتغير أو ثبّت Gradle وAndroid SDK."
  fi
else
  info "سيجري البناء والاختبار على GitHub Actions بعد الرفع. للبناء المحلي الاختياري استخدم TVSHERE_BUILD_LOCAL=1 بعد إعداد Android SDK."
fi

# Stage only the project/release paths. Never stage arbitrary files in the user's home.
git add -A -- \
  .github/workflows/build.yml \
  README.md .gitignore settings.gradle.kts build.gradle.kts gradle.properties \
  app/build.gradle.kts app/proguard-rules.pro app/src/main \
  app/src/test \
  scripts/verify_release.sh \
  BUILD_STATUS.txt \
  TVSHERE_PUSH_TERMUX.sh
for old_file in phase2_pending/RtmpStreamer.kt phase2_pending/STATUS.md; do
  if git ls-files --error-unmatch "$old_file" >/dev/null 2>&1; then
    git add -A -- "$old_file"
  fi
done

if git diff --cached --quiet; then
  info "لا توجد تغييرات جديدة لرفعها."
  exit 0
fi

git diff --cached --check || fail "فشل فحص التغييرات المُجهزة."

if ! git config user.name >/dev/null || ! git config user.email >/dev/null; then
  fail "اضبط هوية Git أولاً: git config user.name \"اسمك\" && git config user.email \"بريدك\""
fi

info "الملفات التي ستدخل في commit:"
git diff --cached --name-status
printf '\n'
read -r -p "هل تؤكد إنشاء commit ورفع التحديث إلى origin/$BRANCH؟ اكتب YES: " CONFIRM
[[ "$CONFIRM" == "YES" ]] || fail "ألغيت الرفع بناءً على اختيارك. بقيت التغييرات محلية فقط."

if git rev-parse --verify HEAD >/dev/null 2>&1; then
  git commit -m "Tvshere 1.1.0-rc1: integrate UVC RTMP audio and overlays"
else
  git commit --allow-empty -m "Initial Tvshere 1.1.0-rc1 source"
fi

info "رفع الفرع $BRANCH إلى GitHub من دون force push…"
git push -u origin "$BRANCH"
info "تم الرفع. راقب GitHub Actions للحصول على APK بعد نجاح البناء والاختبارات."
