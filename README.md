# WEBUNIME TV (Android TV)

Aplikasi Android TV native untuk katalog WEBUNIME, dengan navigasi **remote / D-pad** (Leanback).

## Fitur MVP
- Browse baris: Film, Series, Anime Terbaru, Anime, Anime Movie, Horor
- Detail + pilih episode / server
- Player hybrid:
  - **ExoPlayer** untuk URL media langsung (`.mp4` / `.m3u8` / Wibufile video)
  - **WebView** fallback untuk embed TurboVIP / Cast / Hydrax
- Katalog:
  1. Seed dari `app/src/main/assets/data/*.json`
  2. Saat app dibuka → fetch update dari GitHub raw
  3. Disimpan ke cache lokal

Sumber JSON:
`https://raw.githubusercontent.com/gitgitmiko/WEBUNIME/main/public/data/`

## Prasyarat
- **Android Studio** (sudah terpasang)
- **JDK** — bisa memakai JBR bawaan Android Studio  
  `C:\Program Files\Android\Android Studio\jbr`
- Android SDK (`%LOCALAPPDATA%\Android\Sdk`)
- Emulator **Android TV** atau device TV stick (sideload APK)

## Build (CLI)
```powershell
cd "C:\Users\sjatm\OneDrive\Documents\Project\App WEBUNIME"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug
```

APK debug: `app\build\outputs\apk\debug\app-debug.apk`

## Run di Android Studio
1. File → Open → folder `App WEBUNIME`
2. Tools → Device Manager → buat Virtual Device **TV** (mis. Television 1080p)
3. Run `app`

## Update katalog
1. Di project web WEBUNIME: sync / scrape data baru
2. Commit + push JSON ke repo GitHub WEBUNIME
3. Buka app TV → otomatis fetch JSON terbaru

## Tahap berikutnya (belum di MVP)
- GitHub Actions scrape 1× sehari
- Backend resolve stream agar lebih banyak playback lewat ExoPlayer (remote lebih mulus dari WebView)

## Catatan
- Remote di mode **WebView** belum sehalus ExoPlayer.
- Preferensi server film: TurboVIP → Cast → Hydrax (P2P diurutkan belakangan).
