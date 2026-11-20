# AtvPlayer

Client Android (Kotlin + Jetpack Compose + libVLC) pour récepteurs Enigma2 / OpenATV via **OpenWebIF**.

- Liste de chaînes moderne : picons, EPG now/next, barre de progression, recherche, bouquets
- Lecteur plein écran avec infobar (EPG, description, zapping par glissement haut/bas)
- Pistes audio, sous-titres (+ fichier .srt externe), décalages audio/sous-titres
- Réglages image : saturation, contraste, luminosité (GPU, mémorisés)

## Compiler
**Android Studio** : ouvrir le dossier, Run. 
**Ligne de commande** : `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` (JDK 17 + Android SDK 35).
**GitHub Actions** : pousser sur GitHub, l'APK est dans les artifacts du workflow « Build APK ».

## Configuration
Au premier lancement : IP du récepteur, port web (80), port streaming (8001), identifiants si activés.
