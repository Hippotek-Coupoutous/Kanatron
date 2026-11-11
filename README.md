# Kanatron

Free, open-source music recording for Android 8+. Record over a click, overdub, edit, tune. No ads, no account, no internet access.

**Alpha software: back up recordings you care about.**

Download: https://rootedrobot.com/kanatron

## Build

Requires JDK 17+ and the Android SDK.

```
./gradlew assembleDebug
```

Release builds are signed with a key read from `local.properties` (`kanatron.storeFile`, `kanatron.storePassword`, `kanatron.keyAlias`, `kanatron.keyPassword`); without it, `assembleRelease` produces an unsigned APK.

## Licence

MIT, see [LICENSE](LICENSE). The bundled Big Shoulders Display font is under the SIL Open Font License, see [third_party](third_party/BigShouldersDisplay-OFL.txt).
