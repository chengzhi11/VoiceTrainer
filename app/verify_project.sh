#!/bin/bash

# FeminineVoiceTrainer - Project Verification Script
# This script verifies the integrity and completeness of the Android project

echo "🔍 FeminineVoiceTrainer - Project Verification"
echo "=========================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Verification counters
TOTAL_CHECKS=0
PASSED_CHECKS=0
FAILED_CHECKS=0

# Function to check and report
check_item() {
    local description=$1
    local file_path=$2
    local is_directory=$3

    TOTAL_CHECKS=$((TOTAL_CHECKS + 1))

    if [ "$is_directory" = true ]; then
        if [ -d "$file_path" ]; then
            echo -e "${GREEN}✓${NC} $description"
            PASSED_CHECKS=$((PASSED_CHECKS + 1))
            return 0
        else
            echo -e "${RED}✗${NC} $description - MISSING"
            FAILED_CHECKS=$((FAILED_CHECKS + 1))
            return 1
        fi
    else
        if [ -f "$file_path" ]; then
            echo -e "${GREEN}✓${NC} $description"
            PASSED_CHECKS=$((PASSED_CHECKS + 1))
            return 0
        else
            echo -e "${RED}✗${NC} $description - MISSING"
            FAILED_CHECKS=$((FAILED_CHECKS + 1))
            return 1
        fi
    fi
}

echo "📋 Checking Project Structure..."
echo ""

# Root level files
check_item "Root build.gradle.kts" "build.gradle.kts"
check_item "Root settings.gradle.kts" "settings.gradle.kts"
check_item "Gradle properties" "gradle.properties"
check_item "Gradle wrapper" "gradle/wrapper/gradle-wrapper.properties"
check_item "Git ignore file" ".gitignore"
check_item "Project README" "README.md"
check_item "Verification script" "verify_project.sh"

echo ""
echo "📱 Checking App Module Structure..."
echo ""

# App module structure
check_item "App build.gradle.kts" "app/build.gradle.kts"
check_item "App ProGuard rules" "app/proguard-rules.pro"
check_item "App manifest" "app/src/main/AndroidManifest.xml"

echo ""
echo "🎨 Checking Resource Files..."
echo ""

# Resources
check_item "String resources" "app/src/main/res/values/strings.xml"
check_item "Theme configuration" "app/src/main/res/values/themes.xml"
check_item "Backup rules" "app/src/main/res/xml/backup_rules.xml"
check_item "Data extraction rules" "app/src/main/res/xml/data_extraction_rules.xml"

echo ""
echo "💻 Checking Source Code - Data Layer..."
echo ""

# Data layer
check_item "Recording entity" "app/src/main/java/com/femininevoicetrainer/data/Recording.kt"
check_item "Recording DAO" "app/src/main/java/com/femininevoicetrainer/data/RecordingDao.kt"
check_item "App Database" "app/src/main/java/com/femininevoicetrainer/data/AppDatabase.kt"

echo ""
echo "🔊 Checking Source Code - Audio Layer..."
echo ""

# Audio layer
check_item "Audio Recorder" "app/src/main/java/com/femininevoicetrainer/audio/AudioRecorder.kt"
check_item "Pitch Analyzer" "app/src/main/java/com/femininevoicetrainer/audio/PitchAnalyzer.kt"
check_item "Scoring Algorithm" "app/src/main/java/com/femininevoicetrainer/audio/ScoringAlgorithm.kt"

echo ""
echo "🖼️ Checking Source Code - UI Layer..."
echo ""

# UI layer
check_item "Main Activity" "app/src/main/java/com/femininevoicetrainer/ui/MainActivity.kt"
check_item "Main View Model" "app/src/main/java/com/femininevoicetrainer/ui/MainViewModel.kt"
check_item "Main Screen" "app/src/main/java/com/femininevoicetrainer/ui/MainScreen.kt"
check_item "Theme file" "app/src/main/java/com/femininevoicetrainer/ui/theme/Theme.kt"
check_item "Type file" "app/src/main/java/com/femininevoicetrainer/ui/theme/Type.kt"

echo ""
echo "🧪 Checking Test Files..."
echo ""

# Test files
check_item "Scoring Algorithm Test" "app/src/test/java/com/femininevoicetrainer/audio/ScoringAlgorithmTest.kt"
check_item "Pitch Analyzer Test" "app/src/test/java/com/femininevoicetrainer/audio/PitchAnalyzerTest.kt"
check_item "Recording Test" "app/src/test/java/com/femininevoicetrainer/data/RecordingTest.kt"

echo ""
echo "📊 Verification Summary"
echo "=========================================="
echo "Total Checks: $TOTAL_CHECKS"
echo -e "${GREEN}Passed: $PASSED_CHECKS${NC}"
if [ $FAILED_CHECKS -gt 0 ]; then
    echo -e "${RED}Failed: $FAILED_CHECKS${NC}"
else
    echo "Failed: $FAILED_CHECKS"
fi
echo ""

# Check if all tests passed
if [ $FAILED_CHECKS -eq 0 ]; then
    echo -e "${GREEN}✓ All checks passed! Project structure is complete.${NC}"
    echo ""
    echo "🎉 Project is ready for build and testing!"
    echo ""
    echo "Next steps:"
    echo "1. Build the project: ./gradlew assembleDebug"
    echo "2. Run tests: ./gradlew test"
    echo "3. Install to device: ./gradlew installDebug"
    exit 0
else
    echo -e "${RED}✗ Some checks failed. Please review the missing items above.${NC}"
    echo ""
    echo "❌ Project structure is incomplete. Please ensure all files are present."
    exit 1
fi
