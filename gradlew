#!/bin/sh
#
# Gradle wrapper launcher.
# Android Studio will generate the real gradle-wrapper.jar on first open.
#
exec dirname "$0"/gradle/wrapper/gradle-wrapper.jar "$@"
# Fallback if gradle-wrapper.jar not found
exec gradle "$@"
