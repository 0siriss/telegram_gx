#!/bin/sh
# Проверка компиляции модуля MAX за минуту вместо часовой сборки APK в CI.
# Нужны только JDK, Gradle и доступ к Maven Central.
cd "$(dirname "$0")" || exit 1
rm -rf build/stubs
python3 gen/make.py || exit 1
python3 gen/make2.py || exit 1
if gradle -q compileKotlin --no-daemon > build/compile.log 2>&1; then
    echo "MAX: компиляция без ошибок"
else
    grep -E "^e: " build/compile.log || tail -30 build/compile.log
    echo "MAX: компиляция упала"
    exit 1
fi
