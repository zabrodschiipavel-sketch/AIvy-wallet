#!/bin/bash

# -------------------
# 0. ROOT DIRECTORY CHECK
# -------------------

# Check if the script is being run from the root directory of the repo
if [ ! -f "settings.gradle.kts" ]; then
    echo "ERROR:"
    echo "Please run this script from the root directory of the repo."
    exit 1
fi


./gradlew lintRelease || exit 1
python scripts/quality.py lint build/reports/lint/lint.xml || exit 1
open build/reports/lint/lint.html
echo "[FINISHED] Lint report opened in your default browser."