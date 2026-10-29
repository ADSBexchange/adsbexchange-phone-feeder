# ADS-B Exchange Phone Feeder

[![CI](https://github.com/ADSBexchange/adsbexchange-phone-feeder/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/ADSBexchange/adsbexchange-phone-feeder/actions/workflows/ci.yml?query=branch%3Amain)
[![Security scan](https://github.com/ADSBexchange/adsbexchange-phone-feeder/actions/workflows/security.yml/badge.svg?branch=main)](https://github.com/ADSBexchange/adsbexchange-phone-feeder/actions/workflows/security.yml?query=branch%3Amain)
[![CodeQL](https://github.com/ADSBexchange/adsbexchange-phone-feeder/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/ADSBexchange/adsbexchange-phone-feeder/actions/workflows/codeql.yml?query=branch%3Amain)

Turns an Android phone with an RTL-SDR USB receiver into an ADS-B Exchange feeder. Plug the SDR
in and it feeds: no root, no Termux, no separate driver app. The app opens the SDR through
Android's USB host API and runs [readsb](https://github.com/wiedehopf/readsb) itself.

**Status:** moving toward shippable, not there yet. Builds cleanly (debug and release;
arm64-v8a / armeabi-v7a / x86_64; lint 0 errors) and passes the
[emulator smoke test](#smoke-test-no-sdr-needed). It has fed ADS-B Exchange with a real RTL-SDR on three
phones: a Pixel 7 Pro (overnight, the power test, MLAT synced with 23 feeders), a Samsung Galaxy
S10+ and a Pixel 10 Pro. Older phones (Android 7–10) are still to test. Branding follows adsbexchange.com (see [Branding](#branding)); licensing is pending review
(see [Licensing](#licensing)).

## What it does

- **Plug and play.** One setup screen (what you need, a feeder name, permissions). After that,
  plugging in the SDR opens the app and starts feeding; so does a reboot or an app update, unless
  the user tapped Stop.
- **Always says what's going on.** The Status screen shows one of: this phone can't host USB, plug
  in your SDR (with steps), SDR unplugged, allow access, ready, starting, feeding / connecting /
  can't reach ADS-B Exchange, or stopped with the reason.
- **Receiver location from the phone.** At setup the app averages GPS fixes for up to about 90 s,
  stopping once accuracy is good, and saves the position. It checks again (at most hourly) when
  the app is opened and updates the position if the phone has moved more than 5 km. It can also
  be entered by hand. **Mobile mode** (GPS updates while feeding, for a car or boat) was taken out
  for launch, to keep the Play review to one foreground service type; git tag `with-mobile-mode`
  has the last version with it.
- **MLAT, on by default** (see [MLAT](#mlat)). Takes part in ADS-B Exchange multilateration,
  which is also how the **feeder name** reaches ADSBx: the name (optional, blank by default) is
  the MLAT name shown on ADSBx's MLAT sync pages. **Privacy** is off to start with, as on ADS-B
  Exchange's own feeders (with it on, their linked-receivers page hides the feeder's map marker
  and its feed and stats links); an older install keeps what it had. The public MLAT map lists
  feeders by name either way; with privacy off it also shows the feeder at an
  approximate location: ADSBx publishes positions rounded to 0.01° (about 1 km), never the precise
  one MLAT uses. With privacy on it shows no location. (Checked against the map's `sync.json`,
  2026-10-11: every coordinate has at most two decimals, and private feeders have none.)
- **Live feed:** proof that this phone is sending. It shows how long the ADS-B Exchange connection
  has been up (and how often it dropped), the data rate and total sent, messages decoded, aircraft
  seen this session, and a live chart of messages per second over the last 5 minutes. Also max
  range, signal/noise, dropped samples and the receiver position.
- **Antenna coach** (from the Live feed card): live messages a second, aircraft with a position and
  the furthest one, smoothed so they settle a few seconds after the antenna moves. **Measure this
  spot** averages 30 s, so spots can be compared side by side, best first. It works from the data the
  screen already reads, so it costs nothing extra (`ui/AntennaCoach.kt`).
- **Feeding stopped alerts:** one notification if the phone can't reach ADS-B Exchange for 15
  minutes, or hears no aircraft for 2 hours (a quiet night isn't a fault). It clears itself once
  things recover. There's a switch in Settings, and no new permission prompt: setup already asks for
  notifications (`data/FeedWatchdog.kt`).
- **Charger warning:** a notification, and a warning on Phone health, when the battery is running
  down although the phone has been on a charger. That means it isn't charging and has dropped 3%
  (about 15 minutes of feeding) since it stopped. It's aimed at wireless pads, which can stay
  connected while delivering nothing: one test showed two hours of "not charging" on a pad, at
  ~12% an hour. Short pauses and a phone holding at a charge limit don't trigger it, because the level
  doesn't fall. It shares the alerts switch in Settings (`data/ChargeWatchdog.kt`). The advice it gives
  comes from that test: the cause was the pad being powered from a laptop's USB port. Plugged into a
  wall adapter, the same pad charged the phone to 100% while feeding and held it there all night, at
  25–30 °C. Setup's "You'll need" list says the same. It also says to leave room for air around
  the SDR stick and its adapter. The stick itself runs warm (an RTL-SDR turns about 1–1.5 W into
  heat), and plugged straight into a USB-C OTG adapter it made the adapter hot in testing. Raising
  the phone about 5 mm off the surface, so air reaches underneath, brought it down to warm. (A USB
  extension cable between adapter and SDR would also move the heat away, but the one tried left the
  SDR undetected, probably a charge-only or thin cable, so the app doesn't suggest one.)
- **Feeding doesn't make phones hot.** None of the three test phones got hot from feeding. The
  only time one felt warm was on a wireless charging pad, and the pad was the cause; even then, the
  Pixel 7 Pro held 25–30 °C overnight. The app's text says so: the warning names sun and wireless
  pads as the usual causes, and the pause is a safety stop.
- **Battery protection, a safeguard:** feeding pauses by itself if the battery reaches 45 °C (or
  Android's thermal status reaches "severe"), or the battery is down to 10% and not charging. It
  resumes by itself below 40 °C, or once charging. The status card and the notification say why,
  and an alert goes out unless the alerts switch is off (`data/BatteryGuard.kt`). See "Pausing and
  restarts" below for how. Phone health warns from 40 °C.
- **Crash-loop stop:** if readsb ends 5 times in 15 minutes (a failing dongle, cable or adapter),
  the app stops trying and says "The SDR keeps failing" instead of starting for ever. Re-plugging
  the SDR or tapping Try again starts afresh (`RestartBudget`).
- **Stats uploads (ADS-B Exchange's stats package):** every 5 s, readsb's aircraft list with the
  feeder UUID and signal figures, gzipped and posted to `adsbexchange.com/api/receive/`. This is
  what ADS-B Exchange's own feeder kits send alongside the feed (the `adsbexchange-stats`
  package's `json-status` script), and it uses the same request. Without it ADS-B Exchange lists
  the feeder as "beast-only": the feed arrives, but there are no statistics (aircraft seen,
  positions, range, uptime), and the feeder isn't on the leaderboard or in the Feeder Hub. So
  they're part of feeding, with no switch (as on the desktop feeder, 0.5.21); Settings explains
  them and links the feeder's stats page. About 1–10 GB a month depending on traffic, so they
  pause with the feed on mobile data. Ported from the desktop feeder
  (`service/StatsUploader.kt`).
- **No surprise mobile data:** a phone at home with a SIM falls back to mobile data whenever
  Wi-Fi drops. If it has been on mobile data (or a metered Wi-Fi) for 30 s, feeding pauses the
  same way as the battery pauses, and resumes once it's back on Wi-Fi. A busy site's feed is a
  few GB a month. **Feed over mobile data** in Settings turns this off (`NetworkGuard`).
- **Radar tab:** the aircraft this receiver hears, around it, north always at the top (no
  compass). Range rings; aircraft coloured by altitude on the ADS-B Exchange map's scale, with
  the callsign and altitude beside each; three-minute trails; and, once History has enough data,
  the coverage outline. No map underneath, so a screenshot doesn't show where the receiver is.
  The range is **Auto** (fits the furthest aircraft, zooming in only when everything fits well
  inside the smaller range) or a fixed 25, 50, 100 or 200 nm, remembered; no pinch-zoom or pan.
  Tap an aircraft for its details and a link to the ADS-B Exchange map. It draws only when
  aircraft.json changes, once a second, with no animation (`ui/RadarScreen.kt`, `ui/RadarMath.kt`,
  `data/RadarTrails.kt`).
  **Airports** (as the desktop feeder, 0.5.18): nearby airports as blue-grey rings with their codes,
  under the traffic; large and medium at every range, small fields at 50 nm and closer, at most 30.
  An Airports chip turns them off. The list is OurAirports' (public domain), bundled in
  `assets/airports/airports.tsv` (about 19,500 airports, 470 KB) and made by
  `tools/airports/MakeAirports.java` from a pinned commit, checked against its SHA-256. Nothing is
  fetched at run time (`data/Airports.kt`).
- **Bias tee** (as the desktop feeder, 0.5.20): Settings > Feeding, "Power an amplifier through the
  antenna cable", off by default. It passes readsb's `--enable-biastee`: about 4.5 V on the antenna
  socket for an amplifier at the antenna, on sticks that switch it in software (RTL-SDR Blog V3 and
  V4). readsb is built with `ENABLE_RTLSDR_BIASTEE`, and `rtlsdrClose()` turns the bias tee off
  again (librtlsdr's close leaves it on). The amplifier adds roughly 0.1–0.5 W. Like the gain, it
  applies when the receiver restarts. Not yet tried with a real stick and amplifier.
- **Aircraft tab:** what this receiver hears, nearest first; tap one to open it on the ADSBx map.
- **History tab:** this phone's lifetime contribution, there to keep people feeding:
  - totals: messages received, different aircraft, time feeding, data sent, and the start date;
  - a chart of messages per day over the last 30 days, and, if the phone fed off the charger for
    10 minutes or more, its average power draw that day (what the phone and SDR cost to run, for
    comparing dongles or settings without `adb`);
  - records: longest unbroken run, furthest aircraft, busiest day;
  - **milestones** (10,000 messages, 100 aircraft, 1 day feeding, and so on up), with progress to
    the next one;
  - a **reception range** plot: the furthest aircraft heard in each of 36 directions, with a Reset
    for after the antenna moves;
  - MLAT sync pairs and messages.

  Only time connected to the feed counts. See [History](#history).
- **Phone health:** battery, charging state, temperature, measured battery power (W), thermal
  status, and warnings for running on battery, a charger that isn't charging, heat and battery
  optimisation. "On a charger" and "charging" are told apart, e.g. "on a wireless charger, not
  charging", because Android reports a connected charger even when it isn't charging.
- **Help:** support is ADS-B Exchange's Discord server ("Get help on Discord"). The app links
  `https://www.adsbexchange.com/discord` (`BuildConfig.SUPPORT_URL`), ADS-B Exchange's own
  address, so the invite behind it can change without an app update. Today that redirects to their
  community page; the invite itself is `discord.gg/ad8SSMpWvH` (permanent, the one on their
  homepage). Help also shares a diagnostics
  bundle (device, settings, state, log; includes the UUID, not the location) and shows the readsb
  log. The text asks people to send diagnostics privately, not in a channel, since Discord channels
  are visible to every member and the UUID identifies the feeder.
- **Phone feeders are identifiable.** Every feeder UUID the app creates ends in `ad5bce11` ("ADSB
  cell" in hex), e.g. `f922b4a5-59c9-4b93-b3ee-9396ad5bce11`, so ADS-B Exchange can measure the
  app: `uuid LIKE '%ad5bce11'` on the feed, MLAT and stats. The rest is still a random version 4
  UUID (about 90 random bits). The tag has to be hex because readsb on ADS-B Exchange's end stops
  reading a UUID at the first non-hex character. It goes at the end because feeder pages show the
  first 8 characters. It is easy to fake, so it's good for counting but not for trust. Installs
  from before the tag keep their UUID: changing it would reset a feeder's stats.

## How it works

```
                 ┌──────────────── UI process ─────────────────┐
                 │ MainActivity + Compose UI + MainViewModel    │
                 │ StationLocator (GPS fix, "moved?" check)     │
                 └───────┬─────────────────────────▲────────────┘
            settings.json│ (UI writes)             │ feeder_status.json, readsb JSON,
                         ▼                         │ readsb.log, readsb_exit (all files)
RTL-SDR ─USB─► ┌──────────────── :feeder process ───────────────┐
               │ FeederService (foreground, connectedDevice[+location])
               │   UsbManager fd ─► usb_shim ─► librtlsdr ─► readsb (JNI, own thread)
               │   GpsdServer 127.0.0.1:<port> ──gpsd TPV──► readsb receiver position
               └──────┬──────────────────────────────┬───────────┘
          feed1.adsbexchange.com:30004       127.0.0.1:<port> (Beast, for the MLAT client)
          beast_reduce_plus_out + UUID
```

| Piece | Where |
|---|---|
| Foreground service in `:feeder`: USB, wake lock, readsb, gpsd feed, unplug handling, live notification | `service/FeederService.kt` |
| In-app gpsd look-alike that streams the receiver position to readsb without restarts | `service/GpsdServer.kt` |
| Resume after reboot / app update | `service/BootReceiver.kt` |
| Settings (JSON file, safe across the two processes) and readsb's command line | `data/FeederSettings.kt` |
| Service → UI status file; readsb exit code | `data/FeederStatus.kt`, `cpp/jni_bridge.c` |
| readsb output → reception stats, aircraft list, feed state, last error | `data/ReceiverStatus.kt` |
| GPS fix averaging and "moved?" check | `data/StationLocator.kt` |
| Battery / power / thermal | `data/PhoneHealth.kt` |
| Screen state, plug-and-play logic (auto permission prompt, auto-start) | `ui/MainViewModel.kt` |
| Screens (Compose, Material 3) | `ui/*Screen.kt`, `ui/theme/Theme.kt` |
| USB fd shim, readsb glue, libc compat, native build | `cpp/` |

### Design choices

- **readsb runs as a library in its own process.** Gradle only packages shared libraries, and
  readsb isn't a library: it has global state and ends with `exit()`. In `:feeder`, a readsb exit
  ends only that process. The UI learns what happened from files (status, exit code, JSON, log),
  which survive that abrupt exit, rather than binder IPC.
- **Settings are a JSON file, not SharedPreferences,** because SharedPreferences caches per
  process and a cached `:feeder` process could start with stale settings. Both processes write
  it (the notification's Stop writes from `:feeder`), so writers hold a file lock
  (`settings.lock`) as well as a JVM lock, and every write is flushed to storage before it's
  renamed into place. If `settings.json` is ever lost or damaged, the feeder ID is recovered
  from readsb's copy (`readsb/feeder-id`), so the feeder doesn't turn into a new one, and a
  damaged file is first copied aside (`settings.json.unreadable-<date-time>`). One that exists but
  can't be read isn't written over, and the service won't feed under a blank feeder ID. History
  that can't be read is left alone for that run, a partial aircraft record left by a power cut is
  cut off on load, and appends are synced before the totals count them. (From the desktop
  feeder's code review, 0.5.19.)
- **Pausing and restarts.** readsb can start only once per process, so a pause can't simply stop
  and later restart it. Instead the service writes the reason to `feeder_hold` and stops readsb,
  which ends the `:feeder` process with the service still started. Android restarts the service
  (it is `START_STICKY`) in a new process, which finds the hold and waits in the foreground,
  without readsb, the SDR or wake locks, watching battery broadcasts and, once a minute, the
  thermal status. When the battery is fine it starts readsb as usual. A restart with no hold is an
  unplanned exit; those are counted (`feeder_restarts`) for the crash-loop stop. Any deliberate
  start (the app, a plug-in, boot) clears the count.
- **No logcat in release builds.** readsb's output goes to its log file, which the app redacts
  before showing or sharing it. Debug builds also copy it to logcat (tag `readsb`); release builds
  don't, since logcat ends up in bug reports and is readable over `adb`.
- **The receiver position reaches readsb over gpsd.** readsb uses it for local position decoding
  and to discard aircraft it thinks are too far away (300 nm), so a stale position after the
  phone travels would silently drop real aircraft. The gpsd feed updates it live.
- **The receiver position stays private on the phone.** It is usually someone's home:
  - It is never on readsb's command line. readsb, and the app's own start line, print the whole
    command line to the log, which is shown in the app and goes out with "Share diagnostics".
  - Log lines the app shows or shares also pass through `redactLocation()`, in case readsb
    prints a position.
  - Loopback is shared by every app on the phone, so the gpsd port serves only connections from
    the app's own process (checked in `jni_bridge.c`: one of our sockets must have exactly that
    local and remote end). Other apps get the connection closed before a byte is sent.
  - readsb's Beast output, which the MLAT client reads, is on a loopback port picked afresh for
    each run, not the well-known 30005. Another app holding 30005 can't stop readsb starting. The
    MLAT client checks, with the same test, that the listener is readsb in this process before
    it forwards anything, so another app's stream can't go to ADS-B Exchange under this feeder's
    ID. Other apps can still find the port and read the stream. It carries no position, only the
    aircraft heard; moving it to a Unix socket would close that too.
  - The only place the position leaves the phone is the MLAT handshake to ADS-B Exchange. The stats
    uploads leave out readsb's per-aircraft distance and bearing from the receiver (`r_dst`,
    `r_dir`) unless MLAT is sending the position anyway. If readsb ever wrote those fields in
    another form, the upload is skipped rather than sent with them.
  - It isn't backed up or copied to a new phone (`res/xml/data_extraction_rules.xml`,
    `backup_rules.xml`), so a new phone also starts as a new feeder rather than reusing the ID.
  - The Status screen and the map link round it to about 1 km; only Settings shows it exactly.
  - The feeder ID reaches readsb in a file (`--uuid-file`), not on its command line, so it isn't
    in the log either.
- **Stationary by default.** Continuous GPS on a feeder that doesn't move costs battery and heat,
  and indoor fixes wander. So: one careful fix, and an occasional check.
- **Only foreground location permission.** Location is read only while the app is open. No
  background location permission, and the service has no location type (only `connectedDevice`).
- **No screen-only work while nobody is looking.** Feeding and MLAT run the same whether the
  screen is on or not, but work that only serves the screen stops (no setting to turn it on):
  - readsb writes `aircraft.json` less often while the app isn't on screen: every 5 s while stats
    uploads need it, otherwise once a minute, just enough for the history (`stats.json`, which
    the notification uses, keeps going);
  - the app stops refreshing its screens as soon as it leaves the foreground;
  - the service stops re-reading settings and publishing MLAT counters (it still publishes
    state changes);
  - the notification isn't refreshed while the screen is off. It is updated as soon as the
    screen comes back on.

  The app writes `ui_visible` whenever it is shown or hidden, and again every 30 s while it is
  shown. So after a UI crash, a `ui_visible` file older than 90 s counts as "hidden". The feed
  connection is followed by reading only the new lines of readsb's log each tick instead of
  re-reading its tail.
- **The feed is timed as ADS-B Exchange's own feeder kit times it, and Wi-Fi power saving stays
  on.** Each aircraft is sent at most twice a second (readsb's `--net-beast-reduce-interval 0.5`,
  the kit's `REDUCE_INTERVAL`). Updates go out in batches every 0.2 s (`--net-ro-interval-beast-reduce
  0.2`; the kit uses `--net-ro-interval 0.2`), so ADS-B Exchange gets the same data from a phone as
  from its own feeders. readsb's defaults (0.25 s, flushed within 50 ms) sent twice the updates.
  There's no Wi-Fi high-performance lock any more.

  A power test on a Pixel 7 Pro (2026-10-03, see "Power test") measured these:
  - 1.89 W with the lock and readsb's default timing;
  - 1.89 W without the lock;
  - 1.81 W without the lock and with the feed batched (0.5 s / 1 s);
  - no measurable difference from MLAT.

  The feed didn't drop once without the lock. Most of the power goes to the SDR and decoding,
  not the network.
- **Minimal upstream changes.** readsb: four string patches to `readsb.c` at configure time
  (`main` renamed, process-wide signal ignoring replaced, `aircraft.json` throttled while hidden,
  and range tracking kept on although the position only arrives over gpsd after startup; without
  that, Max range was always 0), and one to `sdr_rtlsdr.c` (the bias tee off when the stick is
  closed). librtlsdr: none (macros redirect its libusb calls).

## History

The feeder service adds up the phone's contribution in `data/HistoryRecorder.kt`, and the History
tab reads the result. The battery cost is negligible:
- **No new wake-ups.** It uses the service's existing 2 s tick.
- **Feeding time and MLAT counters** are read every tick. Both are in memory, so this is cheap.
- **readsb's totals** (`stats.json`) and **an aircraft snapshot** (`aircraft.json`) are read
  every 10 s while the app is on screen, and once a minute otherwise. In the background readsb
  writes `aircraft.json` only once a minute anyway, which catches almost every aircraft: they
  stay in range for minutes.
- **Saves:** every 10 s while on screen, every 5 minutes otherwise, and when feeding stops. If
  the process is killed, at most 5 minutes of counting is lost.

Storage:
- `history.json` holds the totals, records, up to 400 days of daily figures, and today's aircraft.
- `history_aircraft.bin` holds every aircraft address heard, 4 bytes each. It is append-only, so
  a busy site's tens of thousands of aircraft are never rewritten.

Counting rules:
- readsb's and the MLAT client's counters restart from zero with each session or client, so the
  recorder adds the differences.
- Only ICAO addresses count as aircraft.
- Distances over 400 nm are treated as decoding errors and ignored.
- The range plot keeps the furthest position seen in the last minute for each 10° sector, from
  readsb's distance and bearing (`r_dst`, `r_dir`). Reset is requested through the settings,
  because only the feeder service writes the history.
- Clearing the app's data resets History. Cloud backup is off because the location is stored with
  the settings.

**Data sent** is the feed plus MLAT. readsb's `bytes_out` also counts the Beast data our own MLAT
client reads over loopback, which never leaves the phone. So the app takes that out
(`MlatStatus.loopbackBytes`), as of the moment readsb last wrote `stats.json`. Before this fix,
the Live feed card's "data sent" was roughly doubled whenever MLAT ran.

## MLAT

`mlat/` is a Kotlin port of ADSBx's [mlat-client](https://github.com/adsbexchange/mlat-client)
0.4.2 (GPL-3), running in the `:feeder` process next to readsb. It covers what ADSBx feeder kits
use, which run the client with `--no-udp --input-type dump1090`:

| Upstream | Port |
|---|---|
| `modes_message.c`, `modes_crc.c`: Mode S decoding (address, CRC, altitude, CPR odd/even, NUCp) | `ModeS.kt` |
| `modes_reader.c`: Beast input, 12 MHz timestamps moved to frame start, jump detection, filters | `BeastReader.kt` |
| `coordinator.py`: aircraft tracking, sync pairs, MLAT messages, seen/lost and rate reports | `MlatCoordinator.kt` |
| `jsonclient.py`, `net.py`, `receiver.py`: handshake, JSON over TCP with zlib/zlib2, heartbeats, timeouts, feed1/feed2 and 31090/64590 failover | `MlatClient.kt` |

**Sync quality.** MLAT needs very consistent timing between receivers. Every 10 minutes while
connected, the service logs the server's view of this feeder's timing (`MlatSyncLog`, as the
desktop does), e.g. `mlat: synced with 28 nearby feeders, sync outliers 0.3%, bad-sync timeout 0;
last 10 min: 1034 sync pairs and 490 MLAT messages sent, 5 aircraft requested`. So a long unattended
run (screen off, Doze, heat) leaves a record, which Share diagnostics carries. A bad-sync timeout
above 0 means ADS-B Exchange isn't using the feeder's timing for positions until the score decays
(`bad_syncs × 150` in mlat-server, at most 900). The MLAT card then shows a warning. On 8 October,
ADS-B Exchange's sync table had the phone at 28 peers, bad-sync 0 and a 0.1 µs median timing error,
as good as a Raspberry Pi feeder nearby.
In the smoke test, the line is logged every 5 s against the stand-in server, and
`FAKE_MLAT_BAD_SYNC=<n>` makes the stand-in report a bad-sync timeout so the warning can be seen.

Not ported: UDP transport, MLAT results output (not needed yet), Radarcape/SBS/AVR inputs.

It connects to readsb's Beast output (a loopback port picked for each run) only while the server connection is up,
as upstream does. It sends the feeder UUID, the name (or `phonefeeder-<id>` if blank; upstream
requires one), privacy, and the receiver position and altitude.

**When it runs.** MLAT is on by default but only starts when all of these are true
(`data/Mlat.kt`):
- feeding ADS-B Exchange;
- location accurate to 15 m (the ADSBx setup's figure) or entered by hand;
- an antenna altitude is known (GPS, which is height above the WGS84 ellipsoid as mlat-client
  expects, or entered by hand);
- the antenna is within about 15 m of the phone: a box that starts ticked, since on a phone the
  antenna is nearly always on a short cable (1.0.0's unticked box is ignored on update).

Until then the app says exactly what it's waiting for, with a one-tap fix where there is one.

**What the app shows.** MLAT state and uptime; "synced with N nearby feeders" (the server's
`peer_count`); sync outliers; how many aircraft the server asked this feeder for; sync pairs and
MLAT messages sent; bytes sent; and the name as shown on the Leaderboard and MLAT maps. Links go to ADSBx's MLAT
sync status, and to the MLAT map when privacy is off.

## Build

Requirements: Android Studio (AGP 9.4, Gradle 9.8), SDK platform 37, NDK `28.2.13676358`, CMake
3.22.1, and `git` on `PATH`. The first sync clones the four native dependencies into
`app/src/main/cpp/third_party/`.

Command line (Windows, toolchain in `C:\Android`):

```powershell
$env:JAVA_HOME = 'C:\Android\jdk-17'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\Android\tmp'
.\gradlew.bat assembleDebug
```

`local.properties` (not checked in) points at the SDK: `sdk.dir=C\:/Android/Sdk`.

**For Google Play:** build an App Bundle signed with the upload key. The key and its passwords
are never in the repository (`*.jks` and `*.keystore` are ignored). They come from environment
variables, or from the user-level `~/.gradle/gradle.properties`:

```powershell
$env:ADSBX_UPLOAD_KEYSTORE = 'D:\keys\adsbx-upload.jks'   # outside the repo
$env:ADSBX_UPLOAD_KEYSTORE_PASSWORD = '...'
$env:ADSBX_UPLOAD_KEY_ALIAS = 'upload'
$env:ADSBX_UPLOAD_KEY_PASSWORD = '...'
.\gradlew.bat bundleRelease      # app/build/outputs/bundle/release/app-release.aab
```

Without the key, `bundleRelease` stops with an error: a bundle is only for Play, and Play would
reject one signed with anything else. `assembleRelease` still builds an APK for sideloading,
signed with the debug key, with a warning. The bundle includes the native debug symbols, so
Play's crash reports show function names.

**Creating the upload key** (once, in your own terminal, so the passwords never pass through
anyone else). Keep the file outside the repository, in a secrets store, with an offline
copy:

```powershell
C:\Android\jdk-17\bin\keytool.exe -genkeypair -v -keystore D:\keys\adsbx-upload.jks -alias upload `
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=ADS-B Exchange Phone Feeder upload key"
```

It's valid for about 27 years (Play requires past 2033). The keystore is PKCS12, which uses one
password for the store and the key, so set both password variables to it.

**The easy way:** right-click `tools/build-play-bundle.ps1` and choose **Run with PowerShell**. It asks for the key file with a file picker and the password with asterisks, builds the bundle, checks its signature, and shows the key's SHA-256 fingerprint. The password isn't saved anywhere.

**Play App Signing:** at the first upload, let Google generate and hold the app signing key; a
lost upload key can then be reset. Phones with a sideloaded test build (debug key) must
uninstall it before installing from Play, which gives them a new feeder ID and an empty history.

**Dependencies are locked.** Every resolved version, transitive ones included, is recorded in
`app/gradle.lockfile` (the app) and `buildscript-gradle.lockfile` (the build's plugins). The
SHA-256 of every downloaded artifact is in `gradle/verification-metadata.xml`. The Gradle
distribution's checksum is in `gradle-wrapper.properties`, and the committed `gradle-wrapper.jar`
matches Gradle's published checksum. The C libraries are pinned to commits in
`app/src/main/cpp/cmake/deps.cmake`.

A build uses exactly the locked versions and fails if an artifact's checksum doesn't match. To add
or upgrade a library, edit `gradle/libs.versions.toml` and then record the change on purpose:

```powershell
.\gradlew.bat assembleDebug assembleRelease lintDebug testDebugUnitTest --write-locks --write-verification-metadata sha256
```

Review the diff of the three files before committing it. Checksums written this way are trusted
on first use, so check a new dependency on its registry first.

**Checks on GitHub:** every push and pull request to `main` runs the unit tests, lint and a
debug build on Linux, from a clean machine, so a missing checksum or lockfile entry shows up
there (`.github/workflows/ci.yml`). Nothing is signed and no secrets are used. The smoke test
still runs on a PC, since it needs an emulator.

Toolchain notes: on some Windows machines Java can't create its internal pipe under the default
temp directory, hence `JAVA_TOOL_OPTIONS`. `sdkmanager` is now a wrapper around the `android` CLI; use
`cmdline-tools\latest\bin\android.exe --no-metrics sdk install ndk/28.2.13676358`.

## Smoke test (no SDR needed)

```powershell
pwsh -File tools\smoke-test.ps1          # -KeepEmulator leaves it running, -SkipBuild reuses the APK
```

Runs the real app and readsb on a headless Android 16 x86_64 emulator, with the SDR replaced by
a synthetic capture from `tools/adsb-sim/AdsbSim.java` (5 aircraft, correctly encoded DF17
identification / position / velocity frames at 2.4 MSps). It checks:

- first launch shows setup; with no SDR the right screen shows (on emulators, which usually don't
  declare USB host support, "This phone can't use an SDR");
- the status card shows live counts; readsb receives the receiver position **over gpsd**;
- another app can't read the position: adb's shell user (a different UID, like another app)
  gets Beast data from readsb's port as a control, but nothing from the gpsd port. The readsb log
  contains no receiver coordinates;
- the feed path, offline: a TCP listener in the emulator stands in for ADS-B Exchange (debug-only
  `smoke_feed` extra). The status shows "Feeding ADS-B Exchange", the Live feed card shows the
  connection, the listener receives the feed, and readsb's `bytes_out` is more than that by the
  MLAT loopback the app subtracts. No test data ever reaches the real ADS-B Exchange;
- History counts the messages, every aircraft exactly once, the time feeding, MLAT and data sent,
  with the loopback left out, and the History tab shows them;
- the range plot records each direction aircraft were heard in; the milestones card shows; and
  there's no Leaderboard link;
- the MLAT card's name row keeps room for its label next to a long value;
- the antenna coach opens from the Live feed card and shows the live rate;
- the charger warning: with the emulator's battery set to not charging and dropped 4 points, the
  warning appears on Phone health, then clears once it charges again;
- the Aircraft tab lists every aircraft; every decoded callsign, altitude, position (±0.001°),
  ground speed, track and vertical rate matches;
- the Radar tab places every aircraft with a position (its header counts them; the drawing
  itself is a canvas uiautomator can't read, so there's a screenshot);
- MLAT end to end against `tools/mlat-sim/FakeMlatServer.java`, a stand-in MLAT server on the PC
  (the emulator reaches it as 10.0.2.2) that negotiates zlib2 compression, requests every test
  aircraft and reports 7 peers. It checks:
  - the handshake carries the feeder name, UUID, privacy and position (the main feed's UUID,
    with the phone tag, is checked too);
  - sync pairs arrive for the ADS-B aircraft;
  - MLAT messages arrive for a Mode S-only test aircraft (DF11/DF4, no ADS-B positions);
  - the UI shows "MLAT connected… synced with 7 nearby feeders";
- with the app in the background, readsb keeps running but stops rewriting `aircraft.json`, and it
  resumes when the app comes back;
- Stop makes readsb exit normally with no service restart, and the stopped state shows;
- Start right after Stop, while readsb is still shutting down, starts again in a new process
  instead of failing (readsb can start only once per process);
- battery protection: with the emulator's battery at 9% and unplugged, feeding pauses, readsb
  stops, Android restarts the service and it waits in the foreground, and the status card says
  "Paused: battery low". Plugged in again, it resumes (on the emulator that ends at "no SDR");
- the mobile-data pause: with the emulator's Wi-Fi off, feeding pauses with "Paused: not on Wi-Fi",
  and resumes once Wi-Fi is back;
- stats uploads, against `tools/stats-sim/FakeStatsServer.java`, a stand-in for ADS-B Exchange's
  stats endpoint on the PC: they arrive every 5 s, gzipped, with the script's headers, the
  feeder UUID in the header and the body, the aircraft list, and the receiver distances (because
  MLAT is sending the position). The real endpoint is never contacted;
- readsb's Beast output is on a per-run port, not 30005, and MLAT works through the client's
  check that the port is readsb's;
- no app crashes.

The MLAT port also has JVM unit tests (`./gradlew testDebugUnitTest`): decoding against published
ADS-B frames, Beast escaping and timestamps, zlib framing, the handshake layout, and the
coordinator's sync and forwarding rules. `LocationPrivacyTest` checks that the readsb command line
carries no coordinates and that `redactLocation()` hides every readsb position message.

Screenshots and readsb output land in `C:\Android\tmp\smoke`. Debug builds only: the launcher
activity accepts `smoke_ifile`, `smoke_lat`, `smoke_lon`, `smoke_feed` (127.0.0.1 only),
`smoke_mlat` (10.0.2.2 or 127.0.0.1 only) and `smoke_skip_onboarding` extras; release builds
ignore them.

Bugs the smoke test has caught (all fixed): a `%` in a format string crashing the main screen;
readsb logging every message (`--quiet` isn't actually its default); readsb's last log lines lost
at exit; and **the gpsd feed never connecting**, because on Android
`InetAddress.getLoopbackAddress()` is `::1` while readsb dials `127.0.0.1`.

## Security scan

```powershell
pwsh -File tools\security-scan.ps1
```

This runs the pre-release checks: secrets, known vulnerabilities, static analysis and an SBOM.
Each scanner runs in a throwaway Docker container, pinned by digest, with the repository mounted
read-only. Nothing is built or installed:

| Check | Tool |
|---|---|
| Secrets, over every commit and the working tree | gitleaks (`.gitleaks.toml` skips upstream sources and build output) |
| Known vulnerabilities, from the lockfiles | osv-scanner |
| Known vulnerabilities in the C libraries, by pinned commit | OSV's API |
| Static analysis of this project's Kotlin, Java and C | semgrep (rules from semgrep.dev, telemetry off) |
| SBOM | syft, plus the C libraries and the build's plugins (CycloneDX) |

It writes `security/scan-summary.md` and `security/sbom.cdx.json`, to attach to a release
review. Raw output goes to `build/security-scan`. In the SBOM, `scope: required`
marks what ships in the APK and `scope: excluded` marks what only the build uses.

The scan fails on a secret, a known vulnerability in anything that ships in the APK, or an open
static-analysis finding. Findings reviewed as intended are annotated in the code with
`nosemgrep` and the reason. Vulnerabilities in build-only tools are listed but don't fail it.

## Test plan (Pixel 7 / Pixel 10 Pro)

1. Install, open, tap **Get started**, allow location and notifications. Check the location
   appears in Settings (±accuracy, "From GPS").
2. With nothing plugged in, Status shows **Plug in your SDR**.
3. Plug in the SDR. Android asks to open the app: tick **Always**, OK. Feeding should start with
   no further taps. Log shows the tuner (e.g. `Found Rafael Micro R820T tuner`).
4. The status card turns to **Feeding ADS-B Exchange**, and the Live feed card shows data sent.
   Set a feeder name, and confirm the antenna is near the phone. The MLAT line should reach
   "MLAT connected… synced with N nearby feeders" (N depends on other feeders nearby). Then check
   the name on the MLAT sync status page.
5. Unplug the SDR: **Your SDR was unplugged** and an alert. Plug it back in: feeding resumes.
6. Tap Stop, reboot the phone: it should stay stopped. Tap Start, reboot: it should resume.
7. Watch **samples dropped** (USB/CPU can't keep up) and the **power** figure. On battery, the
   difference between feeding and stopped is roughly the SDR's draw; use that to compare sticks.
8. Run the **power test**, on a charger and on battery: does the feeder need its wake lock? It
   also checks the phone doesn't kill the feeder with the screen off, and that readsb restarts on
   a real SDR. See "Power test" below.

Useful logcat tags: `readsb` (debug builds only), `usb_shim`, `FeederService`, `libusb`.

### Power test

It compares the feeder's settings on the phone itself. Polling the phone over wireless adb
would keep it awake, which would skew what's being measured. To unlock it, tap the version
number in Settings > About seven times. **Power test** then appears above About.

The current test asks whether the feeder needs its partial wake lock. Google Play's Android
vitals count a wake lock held for 2 hours or more a day against an app, foreground service or
not, and above 5% of sessions that can lower the app's visibility and add a warning to its
listing. A continuously streaming SDR may keep the phone awake by itself; if the feed holds up
without the lock, the app can drop it.

1. Feed with the SDR plugged in, on a charger or on battery (above 40% to start). Run it both
   ways: most feeders live on a charger, which is where the wake lock matters most for Play.
2. Tap **Start the power test**, turn the screen off, and leave the phone for about an hour.
3. The service runs each variant for 18 minutes of counted time: screen off, connected to the
   feed, and after a 2-minute warm-up. It restarts readsb between variants:
   - the current settings (wake lock held);
   - no wake lock;
   - the current settings again, to show any drift during the test.

   New experiments, such as decoding on the efficient cores, become new variants
   (`PowerVariant`).
4. A notification says when it's done. The results are on the card and in Share diagnostics.

Each phase records:
- **stalls:** gaps of over a minute in the service's 10-second checks, i.e. the CPU slept;
- the message rate and the data sent;
- **samples dropped** by readsb (the SDR outran it);
- feed drops;
- MLAT's worst **bad-sync** timeout (above 0, the server stopped using this feeder's timing);
- on battery only, the power: the battery's charge counter (its coulomb counter) times the
  voltage, and the power readings every 10 s as a second figure.

Without the lock, a phase with no stalls, the same message rate, no extra dropped samples or feed
drops, and bad-sync at 0 means the lock isn't needed, at least on that phone. Stopping feeding,
a battery pause or **Stop the test** ends it early and keeps what was measured; plugging in or
unplugging doesn't. Code: `data/PowerTest.kt`, and `FeederService.stepPowerTest`.

The first test (Pixel 7 Pro, 2026-10-03) compared the Wi-Fi lock and the feed timing; the results
are under Design choices.

## What's unverified

- **Stats uploads against the real endpoint:** only tested against the stand-in, as on the
  desktop. On a phone, the feeder's entry should lose "beast-only" and its stats page
  (Settings > Your ADS-B Exchange stats page) should fill. Whether ADS-B Exchange needs an
  upload every 5 s, or whether one a minute would do for the statistics, the leaderboard and the
  Hub, is a question for them; a minute would save the phone battery and data.
- **Real hardware:** USB open through the fd shim, zero-copy USB buffers (should fall back if
  SELinux blocks them), ARM builds, and Android 7–15.
- **System flows the emulator can't show:** the USB attach prompt and auto-start, unplug/replug,
  boot and app-update resume, and battery-optimisation behaviour.
- **Android 12+ background restrictions** on sticky restarts after a crash (caught and logged).
  The battery pause relies on that restart too. On the Android 16 emulator it happens within a
  second, and the restarted service enters the foreground. If a phone refused, the status stays
  "Paused" and opening the app picks the pause up again.
- **The heat pause on a real phone:** the emulator can't set the battery temperature, so only
  the unit tests cover it.

## Branding

The app follows the adsbexchange.com theme: always dark, black pages, `#29D682` green accents,
`#1D1D1B` panels, `#A0A0A0` secondary text, and the site's DM Sans font (bundled in `res/font`,
SIL Open Font License, see `app/src/main/assets/licenses/DM-Sans-OFL.txt`). The header is the site's: logo mark plus
"ADS-B Exchange", with "Phone Feeder" beneath in green.

| Asset | Where |
|---|---|
| Colours and type | `ui/theme/Theme.kt`, `res/values/colors.xml` |
| Header | `ui/BrandHeader.kt`, logo mark `res/drawable/ic_adsbx_logo.xml` |
| App icon: green faceted stealth plane with signal arcs from the nose, on black | `res/drawable/ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml` (themed icons), `res/mipmap-anydpi/ic_launcher.xml` (Android 7) |
| Notification icon (white silhouette of the app icon) | `res/drawable/ic_stat_feeder.xml` |

The logo mark and icon are vector redraws of the site's logo. Swap in official artwork files when
available. Note the logo PNG measured `#00D979` while the site stylesheet uses `#29D682`; the app
uses the stylesheet value. Package name is `com.adsbexchange.feeder`; it becomes permanent at the
first Play upload.

## Open questions

- **Server-side proof of feeding:** the Live feed card shows what this phone sends, counted on the
  phone. ADSBx's public feeder status (`/myip/`) is by public IP, so it can't tell a phone apart
  from another feeder on the same network. A status lookup by feeder UUID would let the app show
  "ADS-B Exchange is receiving this feeder" from the server's side.
- **MLAT review by the ADSBx team:** the client is a reimplementation, tested against a stand-in
  server, not the real one. It would be worth having the MLAT maintainers check a phone's sync
  quality on the real server, and whether they want phone feeders identified, since the client
  currently reports itself as mlat-client 0.4.2 like the kits.
- **MLAT results back to the phone** (aircraft located by MLAT shown in the app): deferred.
- **Play policy:** the app doesn't hold `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Play restricts it
  to certain kinds of app); "Allow background running" opens the app's App info page, where the
  user chooses Battery > Unrestricted (`requestBatteryExemption()`). The partial wake lock may
  trip Android vitals' excessive wake lock metric; the power test checks whether the lock can
  go. The connected-device foreground service needs a declaration and a demo video; the
  privacy policy is with Legal.
- **Release:** R8 shrinking (unshrunk today).

## Licensing

The app is GPL-3.0-or-later (`LICENSE`). readsb is GPL-3.0-or-later, librtlsdr GPL-2.0-or-later,
and the MLAT client is a Kotlin port of mlat-client (GPL-3.0-or-later, © 2015 Oliver Jowett), so
a distributed build is GPL-3 as a whole. Everyone who gets the app must be able to get its
source: Settings > About links to it (`BuildConfig.SOURCE_URL`) and shows the GPL's notices.
The source is at https://github.com/ADSBexchange/adsbexchange-phone-feeder. The copyright
holder is JETNET, L.L.C.

Settings > About > Open-source licenses lists every component in the APK with its copyright and
license. The license texts ship in `app/src/main/assets/licenses/`. That includes readsb's notice,
which carries the BSD terms of the dump1090 code it contains. Also in the APK: libusb
(LGPL-2.1-or-later), zstd (BSD), DM Sans (SIL OFL), and AndroidX, Kotlin, kotlinx, JSpecify and
Guava listenablefuture (all Apache-2.0). `LicensesTest` fails if a license file is missing, or if
a C library pinned in `deps.cmake` isn't listed.
