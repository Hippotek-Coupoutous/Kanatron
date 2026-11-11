Human writing

This is a "vibe-coded" Android application for recording, tuning and some small editing of audio.
I do not have a coding background, and all the code is written by AI;
Even so, this has been lot of work and learning for me to get to this point.


I wanted something like this as I was frustrated with ads and having multiple applications for this.

Any feedback is much appreciated.


The rest of the readme is written by Claude


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
