#!/usr/bin/env bash
set -uo pipefail

report_dir="app/build/reports/emulator-qa"
mkdir -p "$report_dir"
adb logcat -c

test_status=0
./gradlew connectedDebugAndroidTest --stacktrace || test_status=$?

# Instrumentation hosts close after each test. Launch the production entry point so
# the saved UI tree and screenshot show the actual native app rather than Launcher.
# connectedDebugAndroidTest uninstalls its APKs, so reinstall before collecting evidence.
evidence_status=0
./gradlew installDebug --console=plain || evidence_status=$?
settings_dir='/data/user/0/com.example.javbrowser/shared_prefs'
settings_file="$settings_dir/native_settings.xml"
adb shell run-as com.example.javbrowser mkdir -p "$settings_dir" || evidence_status=$?
settings_xml='<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><boolean name="secure_screen" value="false" /></map>'
printf '%s' "$settings_xml" | \
  adb shell run-as com.example.javbrowser tee "$settings_file" > /dev/null || evidence_status=$?
adb shell am force-stop com.example.javbrowser || true
adb shell am start -W -n com.example.javbrowser/.nativeapp.NativeMainActivity \
  > "$report_dir/launch.txt" 2>&1 || evidence_status=$?
sleep 2
adb exec-out uiautomator dump /dev/tty > "$report_dir/ui.xml" || true
adb exec-out screencap -p > "$report_dir/final-screen.png" || true
adb logcat -d > "$report_dir/logcat.txt" || true

grep -q 'text="Luma"' "$report_dir/ui.xml" || evidence_status=1
test -s "$report_dir/final-screen.png" || evidence_status=1

if (( test_status != 0 )); then
  exit "$test_status"
fi
exit "$evidence_status"
