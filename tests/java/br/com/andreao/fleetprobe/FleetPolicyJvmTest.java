name: ANDREAO Fleet diagnostic APK
on:
  workflow_dispatch:
  push:
    branches: [feature/andreao-fleet-foundation-20261111]
    paths:
      - 'android-fleet/**'
      - 'tests/fleet-*.test.mjs'
      - 'tests/java/**'
      - '.github/workflows/android-fleet-debug.yml'
permissions:
  contents: write
concurrency:
  group: fleet-diagnostic-${{ github.ref }}
  cancel-in-progress: true
jobs:
  assemble-debug:
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - name: Install Android 35 using runner SDK
        run: |
          echo "Android SDK: $ANDROID_HOME"
          SDK_MANAGER="$(command -v sdkmanager || find "$ANDROID_HOME/cmdline-tools" -type f -name sdkmanager | head -1)"
          "$SDK_MANAGER" 'platforms;android-35' 'build-tools;35.0.0'
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.9'
      - name: Compile diagnostic APK
        run: gradle -p android-fleet --no-daemon :app:assembleDebug
      - name: Test real JVM disk-budget and incident retention logic
        run: |
          mkdir -p "$RUNNER_TEMP/fleet-policy"
          javac -d "$RUNNER_TEMP/fleet-policy"             android-fleet/app/src/main/java/br/com/andreao/fleetprobe/FleetStorageMath.java             android-fleet/app/src/main/java/br/com/andreao/fleetprobe/FleetRetentionPlanner.java             android-fleet/app/src/main/java/br/com/andreao/fleetprobe/FleetDataPolicy.java             android-fleet/app/src/main/java/br/com/andreao/fleetprobe/Nv21Downsampler.java             tests/java/br/com/andreao/fleetprobe/FleetPolicyJvmTest.java
          java -cp "$RUNNER_TEMP/fleet-policy" br.com.andreao.fleetprobe.FleetPolicyJvmTest
      - name: Run Android contract tests
        run: node --test tests/fleet-diagnostic-contract.test.mjs tests/fleet-tracker-contract.test.mjs
      - name: Store APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: Andreao-Fleet-Integrado-v2.1
          path: android-fleet/app/build/outputs/apk/debug/app-debug.apk
          retention-days: 14

      - name: Make direct APK accessible in feature branch (no ZIP)
        if: github.ref == 'refs/heads/feature/andreao-fleet-foundation-20261111'
        run: |
          set -euo pipefail
          mkdir -p apk-downloads
          cp android-fleet/app/build/outputs/apk/debug/app-debug.apk apk-downloads/ANDREAO_Fleet_v2.1.apk
          echo "APK checksum:"
          sha256sum apk-downloads/ANDREAO_Fleet_v2.1.apk
          git config user.name 'github-actions[bot]'
          git config user.email '41898282+github-actions[bot]@users.noreply.github.com'
          git add apk-downloads/ANDREAO_Fleet_v2.1.apk
          if git diff --cached --quiet; then echo "APK already up to date"; else
            git commit -m 'build(fleet): add v2.1 direct APK download [skip ci]'
            git push origin HEAD:refs/heads/feature/andreao-fleet-foundation-20261111
          fi
