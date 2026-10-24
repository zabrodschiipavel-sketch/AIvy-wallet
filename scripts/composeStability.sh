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

./gradlew :app:assembleDemo :ci-actions:compose-stability:run -PivyComposeFullReports=true -Pkotlin.incremental=false --no-configuration-cache || exit 1
