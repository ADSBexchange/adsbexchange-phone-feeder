# Releasing

How a change gets from a branch to Google Play. Building the signed bundle and uploading it need
the release manager, who holds the upload key. Everything else is open to any contributor.

## How releases work

- `main` is the only long-lived branch; pull requests go there, and releases are built from it.
- The version is in `defaultConfig` in `app/build.gradle.kts`, and nowhere else: `versionName`
  (`X.Y.Z`, what people see) and `versionCode` (a whole number Play uses to order builds). Every
  upload to Play needs a higher `versionCode` than any bundle uploaded before, on any track, even
  one that was never released.
- A release is a signed App Bundle (`.aab`) uploaded to Play Console, plus an annotated tag
  `vX.Y.Z` on the `main` commit it was built from.
- Play App Signing: Google holds the app signing key and signs what phones install. We sign the
  bundle with the upload key, which only proves the upload is ours. A lost or leaked upload key
  can be reset through Play Console.
- CI builds and tests every pull request and push to `main` (`ci.yml`), and runs the security
  scan (`security.yml`). CI doesn't sign anything or upload to Play.
- Settings > About links to this repository as the app's source (`BuildConfig.SOURCE_URL`), as
  the GPL requires, so it has to be public before a build reaches people outside the team.
- There is no GitHub release with an APK, unless the team decides to offer one. A sideloaded APK
  is signed with a different key from Play's, so Play can't update it: moving to the Play version
  means uninstalling first, which gives the phone a new feeder ID and an empty history.

## Tools

All commands below are PowerShell 7 (`pwsh`), from the repository's root on Windows 10/11 x64.

| Tool | Install | For |
| --- | --- | --- |
| PowerShell 7 | `winget install -e --id Microsoft.PowerShell` | The repository's scripts |
| Git | `winget install -e --id Git.Git` | |
| GitHub CLI | `winget install -e --id GitHub.cli`, then `gh auth login` | Pull requests, checks, tags |
| JDK 17, Android SDK, NDK, CMake | README, [Build](README.md#build) | The build. `tools/build-play-bundle.ps1` expects the JDK in `C:\Android\jdk-17` |
| Android emulator | README, [Smoke test](README.md#smoke-test-no-sdr-needed) | The smoke test |
| Docker Desktop | `winget install -e --id Docker.DockerDesktop` | The security scan |
| The upload key and its password | JETNET's password manager | Signing (release manager only) |

The upload key file and its password never go in the repository, in a pull request, in CI, in a
chat or an email, or to anyone but the release manager. Keep the key file outside the
repository; `tools/build-play-bundle.ps1` refuses one inside it.

## 1. Get a change into main

```powershell
git switch main
git pull --ff-only
git switch -c <short-description>
# ... change, commit ...
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
git push -u origin HEAD
gh pr create --base main --fill
gh pr checks --watch
```

Once checks pass (and someone has looked at it), merge it:

```powershell
gh pr merge --squash --delete-branch
```

## 2. Prepare the release

1. In `app/build.gradle.kts`, raise `versionCode` by one and set `versionName` to `X.Y.Z`, in
   their own pull request into `main` or in the last change's.
2. Once it's merged, check that CI and the security scan passed on `main` for that commit:

   ```powershell
   git switch main
   git pull --ff-only
   gh run list --branch main --commit (git rev-parse HEAD)
   ```

3. Run the smoke test and the security scan on a PC, from the same commit:

   ```powershell
   git status --short                    # nothing listed
   pwsh -File tools/smoke-test.ps1
   pwsh -File tools/security-scan.ps1
   ```

   Both must pass. Keep `security/scan-summary.md` and `security/sbom.cdx.json` with the release
   review; they aren't committed.

## 3. Build and sign (release manager)

From the same clean checkout of `main`, right-click `tools/build-play-bundle.ps1` and choose
**Run with PowerShell** (or `pwsh -File tools/build-play-bundle.ps1`). It asks for the upload key
file with a file picker and its password with asterisks, builds the release bundle, checks its
signature and shows the upload key's SHA-256 fingerprint. The password isn't saved anywhere. The
bundle is `app\build\outputs\bundle\release\app-release.aab`.

Check that the fingerprint it shows matches the upload key's fingerprint saved in the password
manager. Then scan the bundle with Microsoft Defender:

```powershell
& "$env:ProgramFiles\Windows Defender\MpCmdRun.exe" -Scan -ScanType 3 -DisableRemediation `
  -File (Resolve-Path app\build\outputs\bundle\release\app-release.aab)
```

It must report no threats (exit code 0).

## 4. Internal testing

1. In Play Console, open the app's **Internal testing** page (under Test and release) and create
   a release with the `.aab`.
2. On the **App integrity** page, **App signing** tab, check that the upload key certificate's
   SHA-256 is the fingerprint the build showed.
3. Install it from Play on a test phone with an SDR:
   - Settings > About shows the new version and the `main` commit, without `-dirty`;
   - plugged in, it feeds, and MLAT connects;
   - after the first release: updating over the previous Play version keeps the feeder ID,
     settings and history.

## 5. Tag

Tag the commit the bundle was built from:

```powershell
git tag -a vX.Y.Z -m "ADS-B Exchange Phone Feeder X.Y.Z (versionCode N)" (git rev-parse HEAD)
git push origin vX.Y.Z
```

## 6. Release notes

Play shows each release's notes under "What's new" (up to 500 characters per language). Start
from what changed since the last tag:

```powershell
git log --format='- %s' <previous tag>..vX.Y.Z
```

- Write for people who feed, not for developers: what's new and what's fixed.
- State plainly any change to what the app sends, or to a privacy setting or its default, and what
  happens to people who update.

## 7. Release

Promote the internal testing release to closed testing if the change needs wider testing first,
then to production, with the release notes. A staged rollout (a percentage of phones at first)
lets a problem be caught before it reaches everyone; raise it to 100% once crash reports in
**Monitor and improve > Android vitals** look normal.

Afterwards, delete branches that were merged for the release.
