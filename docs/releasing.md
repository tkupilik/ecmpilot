# Android release builds

The first signed distribution build is **0.3.0**, version code **3**, package
**`cz.ecmpilot`**. The root [changelog](../CHANGELOG.md) records each release;
keep subsequent work under Unreleased until another version is prepared.

## Upload key

Google Play takes an **Android App Bundle (`.aab`)** for a new app. The upload key
signs this bundle; Play App Signing signs the APKs delivered by Google. See the
[official signing guide](https://developer.android.com/studio/publish/app-signing)
and [App Bundle overview](https://developer.android.com/guide/app-bundle).

For a first release only, generate a local PKCS12 upload key:

```powershell
python tools/create_upload_key.py --keytool "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
```

The helper generates an RSA-3072 key valid for 10,000 days, using a random
password passed to keytool through its environment, and creates:

- `signing/ecmpilot-upload.p12`: private upload key.
- `signing/release.properties`: local Gradle settings, including its password.
- `signing/ecmpilot-upload-cert.pem`: public upload certificate.

The helper never overwrites existing files or prints the password. Back up the
**keystore and release.properties together** to secure storage outside the working
copy. These files are ignored by Git; a clone alone cannot reproduce signing.
The PEM certificate is public and can be supplied to Play Console when requested.
Do not create another key for an update to the same Play app.

To use an existing key on another machine, restore it and the local configuration.
[release-signing.properties.example](../release-signing.properties.example)
documents the four required properties. `storeFile` is relative to the repository
root. Never put real passwords into the example file.

## Build and verify

```powershell
.\dev.ps1 release -Offline
```

Or, with Java/Android SDK configured:

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:bundleRelease :app:assembleRelease
```

The release build is not debuggable, does not include the debug-only Compose
tooling dependency, and requires the local release-signing configuration. Missing
keys fail packaging rather than silently producing a successful unsigned release.

| Artifact | Build output |
| --- | --- |
| Play upload | `app/build/outputs/bundle/release/app-release.aab` |
| Direct-install release APK | `app/build/outputs/apk/release/app-release.apk` |

Verify the APK with Android SDK `apksigner verify --verbose --print-certs` and
`zipalign -c -P 16 -v 4`. Verify the bundle with JDK `jarsigner -verify`; an upload
certificate is self-signed, so a missing public CA trust chain is expected.
Check package/version, non-debuggable manifest, signer identity and archive
contents; keep SHA-256 hashes alongside the copied artifacts.

The direct APK uses the upload certificate. It need not match Google's eventual
Play distribution certificate, and it differs from the development debug key.
Those channels cannot necessarily update each other in place. Installing over
the existing phone development app is not part of release preparation.

## Source commit and tag

1. Increase versionName and the monotonically increasing versionCode.
2. Finalize the dated changelog and release notes, then run the release checks.
3. Commit the reviewed source/documentation with no signing files or binaries.
4. Package again from the clean commit so embedded version-control metadata
   matches it. If the previous build preceded the commit, explicitly refresh
   that metadata before packaging:

```sh
./gradlew :app:extractReleaseVersionControlInfo --rerun-tasks
./gradlew :app:bundleRelease :app:assembleRelease
```

5. Verify the final artifacts and create an annotated version tag on that exact commit:

```sh
git tag -a v0.3.0 -m "eCMPilot 0.3.0"
```

Check that the working tree is clean and record the commit with the artifact
hashes. Pushing the commit/tag and uploading to Play Console are separate steps;
a local tag does not publish a release or change an existing tag.

The English Play release-note text is in
[releases/0.3.0/en-US.txt](releases/0.3.0/en-US.txt). The Play Console app entry,
Play App Signing, store listing, privacy/data declarations and chosen test or
production track still need to be completed before distribution.
