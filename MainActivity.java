name: Build NFC Manager APK

on:
  workflow_dispatch:
  push:
    branches: ["main", "master"]

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Télécharger le dépôt
        uses: actions/checkout@v4

      - name: Installer Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Installer Gradle 8.7
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.7'

      - name: Installer Android SDK
        uses: android-actions/setup-android@v4
        with:
          packages: 'platform-tools'

      - name: Installer Android 35
        shell: bash
        run: |
          yes | sdkmanager --licenses >/dev/null || true
          sdkmanager --install "platforms;android-35" "build-tools;35.0.0"

      - name: Générer le projet Android à partir des deux fichiers du dépôt
        shell: bash
        run: |
          mkdir -p app/src/main/java/com/example/nfccardreader
          mkdir -p app/src/main/res/values app/src/main/res/drawable
          cp MainActivity.java app/src/main/java/com/example/nfccardreader/MainActivity.java

          cat > settings.gradle <<'SETTINGS'
          pluginManagement {
              repositories {
                  google()
                  mavenCentral()
                  gradlePluginPortal()
              }
          }
          dependencyResolutionManagement {
              repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
              repositories {
                  google()
                  mavenCentral()
              }
          }
          rootProject.name = "NFC-Card-Reader"
          include(":app")
          SETTINGS

          cat > build.gradle <<'BUILD'
          plugins {
              id 'com.android.application' version '8.6.1' apply false
          }
          BUILD

          cat > app/build.gradle <<'APP'
          plugins {
              id 'com.android.application'
          }
          android {
              namespace 'com.example.nfccardreader'
              compileSdk 35
              defaultConfig {
                  applicationId 'com.example.nfccardreader'
                  minSdk 21
                  targetSdk 28
                  versionCode 5
                  versionName '3.0'
              }
          }
          APP

          cat > app/src/main/AndroidManifest.xml <<'MANIFEST'
          <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <uses-permission android:name="android.permission.NFC" />
              <uses-feature android:name="android.hardware.nfc" android:required="false" />
              <application
                  android:theme="@style/AppTheme"
                  android:label="NFC Manager"
                  android:icon="@drawable/ic_nfc_logo"
                  android:roundIcon="@drawable/ic_nfc_logo"
                  android:allowBackup="true">
                  <activity
                      android:name=".MainActivity"
                      android:screenOrientation="unspecified"
                      android:exported="true">
                      <intent-filter>
                          <action android:name="android.intent.action.MAIN" />
                          <category android:name="android.intent.category.LAUNCHER" />
                      </intent-filter>
                  </activity>
              </application>
          </manifest>
          MANIFEST

          cat > app/src/main/res/values/styles.xml <<'STYLES'
          <resources>
              <style name="AppTheme" parent="android:style/Theme.Material.NoActionBar">
                  <item name="android:fontFamily">sans</item>
                  <item name="android:colorAccent">#0084FF</item>
                  <item name="android:windowLightStatusBar">false</item>
                  <item name="android:navigationBarColor">#050E1B</item>
                  <item name="android:windowActionModeOverlay">true</item>
              </style>
          </resources>
          STYLES

          cat > app/src/main/res/drawable/ic_nfc_logo.xml <<'ICON'
          <vector xmlns:android="http://schemas.android.com/apk/res/android"
              android:width="64dp"
              android:height="64dp"
              android:viewportWidth="64"
              android:viewportHeight="64">
              <path android:fillColor="#9EB0F7" android:pathData="M10,0 L54,0 Q64,0 64,10 L64,54 Q64,64 54,64 L10,64 Q0,64 0,54 L0,10 Q0,0 10,0 Z" />
              <path android:fillColor="#00000000" android:strokeColor="#00B9D8" android:strokeWidth="3.5" android:strokeLineCap="round" android:pathData="M10,17 Q18,7 29,10" />
              <path android:fillColor="#00000000" android:strokeColor="#00B9D8" android:strokeWidth="3.5" android:strokeLineCap="round" android:pathData="M14,23 Q20,16 27,18" />
              <path android:fillColor="#00000000" android:strokeColor="#00B9D8" android:strokeWidth="3.5" android:strokeLineCap="round" android:pathData="M18,29 Q22,24 26,26" />
              <path android:fillColor="#00000000" android:strokeColor="#07101D" android:strokeWidth="3.2" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="M20,24 Q17,25 18,28 L26,47 Q27,50 30,49 L49,39 Q52,37 50,34 L42,17 Q40,14 37,16 Z" />
          </vector>
          ICON

          gradle wrapper --gradle-version 8.7
          chmod +x gradlew

      - name: Compiler l'APK
        run: ./gradlew assembleDebug --no-daemon

      - name: Vérifier et préparer l'APK
        shell: bash
        run: |
          echo "Recherche des APK produits par Gradle :"
          if [ -d app/build/outputs ]; then
            find app/build/outputs -type f -name '*.apk' -print
          else
            echo "Le dossier app/build/outputs n'existe pas."
          fi

          APK_PATH="$(find app/build/outputs -type f -name 'app-debug.apk' -print -quit 2>/dev/null || true)"
          if [ -z "$APK_PATH" ]; then
            echo "ERREUR : Gradle n'a pas produit app-debug.apk."
            echo "Vérifie les étapes précédentes de compilation."
            exit 1
          fi

          mkdir -p dist
          cp "$APK_PATH" dist/NFC-Manager-debug.apk
          test -s dist/NFC-Manager-debug.apk
          echo "APK prêt : dist/NFC-Manager-debug.apk"
          ls -lh dist/NFC-Manager-debug.apk

      - name: Publier l'APK en artefact téléchargeable
        uses: actions/upload-artifact@v4
        with:
          name: NFC-Manager-debug-apk
          path: dist/NFC-Manager-debug.apk
          if-no-files-found: error
          retention-days: 14
