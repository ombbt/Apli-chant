# Apli Chant

Application Android pour s'entraîner au chant.

## Fonctionnalités

1. **Fichiers** : choisir une musique (MP3 ou WAV) et un backing track (MP3 ou WAV).
2. **Extrait** : sélectionner un passage sur la forme d'onde (curseur double + réglages fins ±0,1 s / ±1 s),
   puis écouter l'extrait de la musique ou du backing track.
3. **Enregistrement** : enregistrer sa voix au micro pendant la lecture du backing track sur l'extrait
   (vu-mètre du micro affiché). Utilisez des écouteurs pour que le micro ne capte pas le backing.
4. **Réécoute** : chaque prise est conservée ; on peut écouter la **voix seule** ou la **voix + backing**,
   régler le volume de chacun et compenser la latence du téléphone (décalage de la voix).

Les fichiers choisis et la sélection sont mémorisés d'une session à l'autre.

## Installer l'APK

À chaque push, la GitHub Action **Build APK** compile l'application : ouvrez l'onglet *Actions* du dépôt,
choisissez la dernière exécution et téléchargez l'artefact `apli-chant-apk` (un zip contenant `app-debug.apk`).
Copiez l'APK sur le téléphone et ouvrez-le (autorisez l'installation depuis des sources inconnues).

## Compiler soi-même

Avec Android Studio (ou le SDK Android + JDK 17) :

```
./gradlew assembleDebug
```

L'APK est généré dans `app/build/outputs/apk/debug/app-debug.apk`.

## Détails techniques

- Kotlin + Jetpack Compose (Material 3), Android 8.0+ (API 26).
- Décodage MP3/WAV avec `MediaExtractor`/`MediaCodec`, ré-échantillonnage à 44,1 kHz.
- Lecture via `AudioTrack`, enregistrement via `AudioRecord` (source `VOICE_RECOGNITION`, sans AGC).
- Les prises sont stockées en WAV mono 16 bits dans le stockage interne de l'application.
