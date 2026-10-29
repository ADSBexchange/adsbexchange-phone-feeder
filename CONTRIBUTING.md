# Contributing

Thanks for helping with the ADS-B Exchange Phone Feeder. By taking part you agree to the
[code of conduct](CODE_OF_CONDUCT.md).

## Where to go

- **Help with the app:** ADS-B Exchange's Discord server (Settings > Help > Get help on Discord in
  the app).
- **A security problem:** report it privately, as [SECURITY.md](SECURITY.md) describes, not in an issue.
- **A bug or an idea:** open an issue with one of the templates.

Diagnostics: "Share diagnostics" and the log (Settings > Help) include your feeder's ID. They
leave out your location, but don't paste them into an issue; a maintainer will ask for what's
needed privately.

## Pull requests

- Branch from `main` and open the pull request into `main`. There is no other long-lived branch.
- For anything larger than a fix, open an issue first so the approach can be agreed before the work.
- Keep a pull request to one change, and match the style of the code around it.
- Checks run on every pull request: `.github/workflows/ci.yml` runs the unit tests, lint and a
  debug build on Linux, and `.github/workflows/security.yml` runs the security scan
  (`tools/security-scan.ps1`: secrets, known vulnerabilities, static analysis). Once the
  repository is public, CodeQL runs too. For contributors outside the ADS-B Exchange
  organisation, a maintainer approves the run first.

Before opening one, build and test locally as the README's [Build](README.md#build) section
describes:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

For a change to what the app does, also run the emulator smoke test (Windows, PowerShell 7, the
toolchain in `C:\Android` or `-Toolchain <folder>`; see the README's
[Smoke test](README.md#smoke-test-no-sdr-needed)):

```powershell
pwsh -File tools/smoke-test.ps1
```

- **Dependencies are locked.** Every resolved version is in `app/gradle.lockfile` and
  `buildscript-gradle.lockfile`, and the SHA-256 of every downloaded artifact is in
  `gradle/verification-metadata.xml`. A dependency change edits `gradle/libs.versions.toml` and
  updates all three together (the README has the command); review their diff before committing
  it, and check a new dependency on its registry first. A change to a C library updates its pinned
  commit in `app/src/main/cpp/cmake/deps.cmake`.
- **Tests never reach ADS-B Exchange.** Unit tests and the smoke test use stand-ins on the PC or
  in the emulator for the feed, MLAT and stats servers.
- **Location stays private.** Nothing may log or send the receiver's position beyond what the
  README and the app's "What ADS-B Exchange receives" describe. The position never goes on
  readsb's command line or into the log, so it stays out of "Share diagnostics".
- **Nothing secret in the repository.** The Play upload key and its passwords never go in the
  repository, in a pull request, or in CI.

## Releases

Maintainers build, sign and upload releases to Google Play from `main`, as
[RELEASING.md](RELEASING.md) describes.

## Licence

The app is GPL-3.0-or-later. Contributions are accepted under the same licence.
