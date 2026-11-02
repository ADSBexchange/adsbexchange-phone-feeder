<#
.SYNOPSIS
  End-to-end smoke test without a dongle: runs readsb inside the debug app on an emulator,
  feeding it a synthetic 1090 MHz capture, and checks the decoded aircraft.

.DESCRIPTION
  1. Builds the debug APK (skip with -SkipBuild).
  2. Generates tools/adsb-sim output (synthetic UC8 I/Q + expected decode) if missing.
  3. Creates and boots a headless x86_64 emulator unless a device is already connected.
  4. Installs the app, copies the capture into its files dir, and starts the feeder through the
     debug-only smoke_ifile intent extra. It never feeds the real ADS-B Exchange: the feed goes to
     a TCP listener inside the emulator that stands in for it (smoke_feed extra).
  5. Waits for readsb to reach the end of the file, pulls aircraft.json / stats.json / the log,
     and compares every aircraft against the expected values. Also checks the app reports the
     feed connection and that the bytes readsb sent arrived at the listener.
  6. Runs the capture again and taps Stop: readsb must exit normally with no service restart.
  7. Runs it again on a 9% battery: feeding pauses, the restarted service waits, and charging resumes it.

  Exit code 0 = all aircraft decoded correctly, no app crash, UI showed live status, Stop works.

.EXAMPLE
  pwsh -File tools\smoke-test.ps1    # PowerShell 7: Windows PowerShell 5.1 mangles quotes passed to adb
#>
param(
    [string]$Toolchain = 'C:\Android',
    [string]$Avd = 'phonereceiver-smoke',
    [double]$Seconds = 75,
    [switch]$SkipBuild,
    [switch]$SkipStopTest,
    [switch]$KeepEmulator
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $Toolchain 'tmp\smoke'
New-Item -ItemType Directory -Force $out | Out-Null

$env:JAVA_HOME = Join-Path $Toolchain 'jdk-17'
$env:ANDROID_HOME = Join-Path $Toolchain 'Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_AVD_HOME = Join-Path $Toolchain 'avd'
# Java's internal pipe fails under the default %TEMP% on this machine; see README.
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$(Join-Path $Toolchain 'tmp')"

$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
$pkg = 'com.adsbexchange.phonefeeder'
# The code stays in com.adsbexchange.feeder, so components need their full class names.
$activity = "$pkg/com.adsbexchange.feeder.MainActivity"
$failures = 0

function Step($msg) { Write-Host "`n== $msg" -ForegroundColor Cyan }
function Adb { & $adb -s $serial @args }
function Shell([string]$cmd) { (& $adb -s $serial shell $cmd) -join "`n" }

# --- 1. build ---------------------------------------------------------------------------
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not $SkipBuild) {
    Step 'Building debug APK'
    Push-Location $root
    try { & .\gradlew.bat assembleDebug -q --console=plain; if ($LASTEXITCODE) { throw 'build failed' } }
    finally { Pop-Location }
}

# --- 2. synthetic capture ---------------------------------------------------------------
$iq = Join-Path $out 'smoke.iq'
$expectedFile = Join-Path $out 'expected.json'
$simSrc = Join-Path (Join-Path (Join-Path $root "tools") "adsb-sim") "AdsbSim.java"
if (-not (Test-Path $iq) -or -not (Test-Path $expectedFile) -or
    (Get-Item $simSrc).LastWriteTime -gt (Get-Item $iq).LastWriteTime -or
    ((Get-Content $expectedFile -Raw | ConvertFrom-Json).seconds -ne $Seconds)) {
    Step 'Generating synthetic capture'
    & (Join-Path $env:JAVA_HOME 'bin\java.exe') (Join-Path $root 'tools\adsb-sim\AdsbSim.java') $iq $expectedFile $Seconds
    if ($LASTEXITCODE) { throw 'AdsbSim failed' }
}
$expected = Get-Content $expectedFile -Raw | ConvertFrom-Json

# --- 3. emulator ------------------------------------------------------------------------
# The test wipes the app's data, so it only ever runs on an emulator, even with a phone plugged in:
# every adb call names the emulator's serial.
function EmulatorSerial { (& $adb devices) | ForEach-Object { if ($_ -match '^(emulator-\d+)\s+device$') { $Matches[1] } } | Select-Object -First 1 }
$startedEmulator = $false
$serial = EmulatorSerial
if (-not $serial) {
    $avdDir = Join-Path $env:ANDROID_AVD_HOME "$Avd.avd"
    if (-not (Test-Path $avdDir)) {
        Step "Creating AVD $Avd"
        New-Item -ItemType Directory -Force $avdDir | Out-Null
        Set-Content -Encoding ascii (Join-Path $env:ANDROID_AVD_HOME "$Avd.ini") @"
avd.ini.encoding=UTF-8
path=$avdDir
target=android-36
"@
        Set-Content -Encoding ascii (Join-Path $avdDir 'config.ini') @"
AvdId=$Avd
avd.ini.displayname=$Avd
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=4
image.sysdir.1=system-images\android-36\default\x86_64\
tag.id=default
hw.ramSize=2048
disk.dataPartition.size=4G
hw.lcd.width=1080
hw.lcd.height=2400
hw.lcd.density=420
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
"@
    }
    Step 'Booting emulator (headless)'
    Start-Process -FilePath $emulator -WindowStyle Hidden -ArgumentList @(
        '-avd', $Avd, '-no-window', '-no-audio', '-no-boot-anim', '-no-snapshot', '-gpu', 'swiftshader_indirect'
    )
    $startedEmulator = $true
    $deadline = (Get-Date).AddMinutes(5)
    while (-not ($serial = EmulatorSerial)) {
        if ((Get-Date) -gt $deadline) { throw 'emulator did not appear in adb in 5 minutes' }
        Start-Sleep -Seconds 3
    }
    while ((Shell 'getprop sys.boot_completed').Trim() -ne '1') {
        if ((Get-Date) -gt $deadline) { throw 'emulator did not finish booting in 5 minutes' }
        Start-Sleep -Seconds 3
    }
}
Write-Host "Device: $serial, $((Shell 'getprop ro.product.model').Trim()), Android $((Shell 'getprop ro.build.version.release').Trim()), ABI $((Shell 'getprop ro.product.cpu.abi').Trim())"
# The emulator keeps its Wi-Fi setting between runs, and the mobile-data section turns it off and on.
# Start every run on Wi-Fi, or feeding pauses for mobile data from the first second.
Shell 'svc wifi enable' | Out-Null
for ($i = 0; $i -lt 30 -and -not ((Shell 'dumpsys connectivity') -match 'Active default network: \d+' -and
        (Shell 'dumpsys wifi') -match 'Wi-Fi is enabled'); $i++) { Start-Sleep -Seconds 1 }
Start-Sleep -Seconds 3


function UiXml {
    # uiautomator occasionally fails to get the window ("null root node"); retry.
    for ($i = 0; $i -lt 3; $i++) {
        Shell 'rm -f /data/local/tmp/ui.xml; uiautomator dump /data/local/tmp/ui.xml' | Out-Null
        $x = Shell 'cat /data/local/tmp/ui.xml'
        if ($x -match '<hierarchy') { return $x }
        Start-Sleep -Seconds 1
    }
    return ''
}
function UiHas([string]$pattern) { (UiXml) -match $pattern }
function Screenshot([string]$name) {
    Shell 'screencap -p /data/local/tmp/shot.png' | Out-Null
    Adb pull /data/local/tmp/shot.png (Join-Path $out $name) | Out-Null
}
# Taps the centre of the first on-screen element whose text is exactly $text.
function TapText([string]$text) {
    $m = [regex]::Match((UiXml), 'text="' + [regex]::Escape($text) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { return $false }
    $x = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2
    $y = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
    Shell "input tap $x $y" | Out-Null
    return $true
}
function Check([bool]$ok, [string]$what) {
    if ($ok) { Write-Host "  ok   $what" } else { Write-Host "  FAIL $what" -ForegroundColor Red; $script:failures++ }
}
function StartSmokeRun([string]$feed = '', [string]$mlat = '', [string]$stats = '') {
    $feedArg = if ($feed) { " --es smoke_feed $feed" } else { '' }
    if ($mlat) { $feedArg += " --es smoke_mlat $mlat" }
    if ($stats) { $feedArg += " --es smoke_stats $stats" }
    Shell ("am start -n $activity --es smoke_ifile smoke.iq " +
           "--ed smoke_lat $($rx.lat) --ed smoke_lon $($rx.lon)$feedArg") | Out-Null
}
# Wait for the bridge's exit line. Don't poll for the :feeder process instead: after readsb
# exits, Android restarts the sticky service and may keep that process cached.
function ReadsbExited { (Shell "run-as $pkg cat files/readsb/readsb.log") -match '\[feeder\] readsb exited' }
# The feeder's partial wake lock, while held: dumpsys power's "Wake Locks" list. dumpsys can briefly
# leave a held lock out on the emulator, so a "held" answer gets a few tries.
function WakeLockListed { [bool]((Shell 'dumpsys power') -split "`n" | Where-Object { $_ -match "^\s+PARTIAL_WAKE_LOCK\s+'AdsbxFeeder:feeder'" }) }
function WakeLockHeld {
    for ($i = 0; $i -lt 4; $i++) { if (WakeLockListed) { return $true }; Start-Sleep -Seconds 1 }
    $false
}

# --- 4. install, first-run screens ------------------------------------------------------
Step 'Installing app (fresh data) and copying capture'
Adb install -r $apk | Out-Null
Shell "pm clear $pkg" | Out-Null
Adb push $iq /data/local/tmp/smoke.iq | Out-Null
Shell "cat /data/local/tmp/smoke.iq | run-as $pkg sh -c 'mkdir -p files && cat > files/smoke.iq'" | Out-Null
Shell 'rm /data/local/tmp/smoke.iq' | Out-Null
Shell 'logcat -b crash -c' | Out-Null
$rx = $expected.receiver

Step 'First-run screens'
Shell "am start -n $activity" | Out-Null
Start-Sleep -Seconds 4
Check (UiHas 'text="Get started"') 'onboarding shown on first launch'
Screenshot 'onboarding.png'
Shell "am start -n $activity --ez smoke_skip_onboarding true" | Out-Null
Start-Sleep -Seconds 3
$usbHost = (Shell 'pm list features') -match 'android.hardware.usb.host'
if ($usbHost) {
    Check (UiHas 'text="Plug in your SDR"') 'no SDR attached: "Plug in your SDR" shown'
} else {
    # Emulator images usually don't declare USB host support; the app must say so.
    Check (UiHas 'text="This phone can.{1,6}t use an SDR"') 'no USB host: "This phone can''t use an SDR" shown'
}
Screenshot 'no-sdr.png'

# A feeder name for the MLAT handshake check, and a stand-in for the ADS-B Exchange MLAT server
# on this PC. The emulator reaches the PC's loopback as 10.0.2.2.
Shell "run-as $pkg sed -i 's/`"name`": `"`"/`"name`": `"smoke-test`"/' files/settings.json" | Out-Null
$mlatPort = 31999
$mlatSummary = Join-Path $out 'mlat-summary.json'
Remove-Item $mlatSummary -ErrorAction SilentlyContinue
$wantedHex = @($expected.aircraft | ForEach-Object { $_.hex }) + $expected.modes_only.hex
$mlatServer = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin\java.exe') -WindowStyle Hidden -PassThru `
    -ArgumentList (@((Join-Path $root 'tools\mlat-sim\FakeMlatServer.java'), $mlatPort, $mlatSummary, ($Seconds + 40)) + $wantedHex)
# And a stand-in for ADS-B Exchange's stats endpoint (what the stats package uploads to).
$statsPort = 31998
$statsSummary = Join-Path $out 'stats-summary.json'
Remove-Item $statsSummary -ErrorAction SilentlyContinue
$statsServer = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin\java.exe') -WindowStyle Hidden -PassThru `
    -ArgumentList @((Join-Path $root 'tools\stats-sim\FakeStatsServer.java'), $statsPort, $statsSummary, ($Seconds + 40))
Start-Sleep -Seconds 4 # single-file java compiles before it listens

# --- 5. decode run, feeding a local stand-in for ADS-B Exchange -------------------------
# A TCP listener inside the emulator plays ADSBx: the app's feed connector is pointed at it with
# the debug-only smoke_feed extra, so the whole feed path is exercised without real traffic.
$sinkPort = 30100
Step "Feeding the capture to a local sink on port $sinkPort ($Seconds s at real-time speed)"
Shell 'rm -f /data/local/tmp/sink.bin' | Out-Null
$sink = Start-Process -FilePath $adb -ArgumentList @('-s', $serial, 'shell', "nc -l -p $sinkPort > /data/local/tmp/sink.bin") `
    -WindowStyle Hidden -PassThru
Start-Sleep -Seconds 1
StartSmokeRun "127.0.0.1,$sinkPort" "10.0.2.2,$mlatPort" "http://10.0.2.2:$statsPort/api/receive/"
# Early: each UI dump takes 2-3 s and the checks below must finish inside the capture.
Start-Sleep -Seconds 5
Check ([bool](Shell "pidof $pkg`:feeder").Trim()) 'feeder process running'
$startLog = Shell "run-as $pkg cat files/readsb/readsb.log"
Check ($startLog -match '--net-beast-reduce-interval 0\.5 --net-ro-interval-beast-reduce 0\.2' -and (WakeLockHeld)) `
    'normal settings: wake lock held, feed timed as the ADS-B Exchange kit (0.5 s / 0.2 s)'
$ui = UiXml
Check ($ui -match 'text="Feeding ADS-B Exchange"') 'status card: "Feeding ADS-B Exchange" (connected to the sink)'
$body = [regex]::Match($ui, 'text="(\d+ aircraft \S+ \d+ messages a second)"').Groups[1].Value
Check ([bool]$body) "status card shows live counts ($body)"
Check ($ui -match 'text="Live feed"') 'Live feed card shown'
$conn = [regex]::Match($ui, 'text="(Connected to ADS-B Exchange for [^"]*)"').Groups[1].Value
Check ([bool]$conn) "feed connection shown ($conn)"
Screenshot 'status.png'
$receiver = (Shell "run-as $pkg cat files/readsb/json/receiver.json") | ConvertFrom-Json
Check ($receiver -and [math]::Abs($receiver.lat - $rx.lat) -lt 0.0001 -and [math]::Abs($receiver.lon - $rx.lon) -lt 0.0001) `
    "readsb got the receiver position over gpsd ($($receiver.lat), $($receiver.lon))"
# Any app can reach 127.0.0.1, so the gpsd feed must serve readsb only. adb's shell user stands in
# for another app: it gets Beast data from readsb's port (control) but nothing from the gpsd port.
$runStartLog = Shell "run-as $pkg cat files/readsb/readsb.log"
$gpsdPort = [regex]::Match($runStartLog, '127\.0\.0\.1,(\d+),gpsd_in').Groups[1].Value
# readsb's Beast output (for the MLAT client) is on a port picked for this run, not 30005.
$beastPort = [regex]::Match($runStartLog, '--net-bo-port (\d+)').Groups[1].Value
Check ($beastPort -and $beastPort -ne '30005') "Beast output on a per-run port ($beastPort), not the well-known 30005"
$beastBytes = [int]((Shell "timeout 3 nc 127.0.0.1 $beastPort | head -c 4096 | wc -c").Trim())
$gpsdReply = (Shell "echo '?WATCH={}' | timeout 3 nc 127.0.0.1 $gpsdPort").Trim()
Check ($beastBytes -gt 0) "another app can read readsb's Beast port (control: $beastBytes bytes)"
Check ($gpsdPort -and -not $gpsdReply) "another app gets nothing from the gpsd port $gpsdPort $(if ($gpsdReply) { "(got: $gpsdReply)" })"
# The MLAT part of the Live feed card is further down: scroll to it in small, slow steps (a
# single long swipe can fling right past it).
$mlatLine = ''
for ($i = 0; $i -lt 6 -and -not $mlatLine; $i++) {
    $ui = UiXml
    $mlatLine = [regex]::Match($ui, 'text="(MLAT connected for [^"]*)"').Groups[1].Value
    if (-not $mlatLine) { Shell 'input swipe 540 1600 540 1100 600' | Out-Null; Start-Sleep -Seconds 1 }
}
Check ([bool]$mlatLine) "MLAT connected in the UI ($mlatLine)"
Check ($mlatLine -match 'synced with 7 nearby feeders') 'MLAT shows the server-reported peer count'
Screenshot 'mlat.png'
# The antenna coach opens from the bottom of the Live feed card, and shows live numbers.
for ($i = 0; $i -lt 6 -and -not (UiHas 'text="Find the best antenna spot"'); $i++) {
    Shell 'input swipe 540 1600 540 1000 500' | Out-Null; Start-Sleep -Seconds 1
}
# A long value must not squeeze its label: the MLAT name row ("Shown on the Leaderboard and MLAT
# maps as" + "smoke-test (location hidden on the MLAT map)") once left the label one letter wide.
$nameRow = [regex]::Match((UiXml), 'text="Shown on the Leaderboard and MLAT maps as"[^>]*bounds="\[(\d+),\d+\]\[(\d+),\d+\]"')
$labelWidth = if ($nameRow.Success) { [int]$nameRow.Groups[2].Value - [int]$nameRow.Groups[1].Value } else { 0 }
Check ($labelWidth -ge 300) "MLAT name row keeps room for its label ($labelWidth px wide)"
Screenshot 'mlat-name.png'
Check (TapText 'Find the best antenna spot') 'opened the antenna coach'
Start-Sleep -Seconds 3
$coachUi = UiXml
$coachRate = [regex]::Match($coachUi, 'text="(\d+)"(?:(?!text=).)*?text="messages a second"').Groups[1].Value
Check ([bool]$coachRate -and $coachUi -match 'text="Measure this spot \(30 s\)"') "antenna coach shows the live rate ($coachRate messages a second)"
Screenshot 'coach.png'
TapText 'Close' | Out-Null
Start-Sleep -Seconds 1
1..3 | ForEach-Object { Shell 'input swipe 540 600 540 1800 300' | Out-Null }
Check (TapText 'Aircraft') 'opened the Aircraft tab'
Start-Sleep -Seconds 2
$ui = UiXml
$listed = @($expected.aircraft | Where-Object { $ui -match ('text="' + $_.flight + '"') }).Count
Check ($listed -eq $expected.aircraft.Count) "Aircraft tab lists $listed of $($expected.aircraft.Count) callsigns"
Screenshot 'aircraft.png'
# The radar draws on a canvas, which uiautomator can't see; its header counts what's on it.
Check (TapText 'Radar') 'opened the Radar tab'
$withPos = @($expected.aircraft).Count
# The radar redraws every second, which can leave uiautomator's dump empty: give it a few tries.
$onRadar = ''
for ($i = 0; $i -lt 5 -and $onRadar -ne "$withPos"; $i++) {
    Start-Sleep -Seconds 2
    $onRadar = [regex]::Match((UiXml), 'text="\d+ aircraft .{1,6} (\d+) on the radar').Groups[1].Value
}
Check ($onRadar -eq "$withPos") "Radar tab places $onRadar of the $withPos aircraft with a position"
Screenshot 'radar.png'
TapText 'Status' | Out-Null

$deadline = (Get-Date).AddSeconds($Seconds + 30)
while (-not (ReadsbExited)) {
    if ((Get-Date) -gt $deadline) { Check $false 'readsb reached the end of the capture'; break }
    Start-Sleep -Seconds 1
}

# The feed must have left readsb and arrived at the sink.
$stats = (Shell "run-as $pkg cat files/readsb/json/stats.json") | ConvertFrom-Json
$sent = [long]$stats.total.remote.bytes_out
# readsb tracks range even though the position only reaches it over gpsd, after startup (readsb
# patch 4 in CMakeLists.txt). Without it, max_distance (the Live feed card's Max range) stayed 0.
$maxRangeNm = [double]$stats.total.max_distance / 1852
Check ($maxRangeNm -gt 1) ("readsb tracks range: max distance {0:N0} nm" -f $maxRangeNm)
$received = [long]((Shell 'stat -c %s /data/local/tmp/sink.bin').Trim())
Check ($sent -gt 0 -and $received -gt 0) "feed data sent by readsb ($sent bytes) and received by the sink ($received bytes)"
# readsb's bytes_out also counts the Beast data our MLAT client reads on loopback; the app takes
# that out, and what's left has to match what actually reached the feed.
$loopback = [long](((Shell "run-as $pkg cat files/feeder_status.json") | ConvertFrom-Json).mlat.loopbackBytes)
# (No exact comparison here: at the end of the capture readsb writes a last stats.json as it exits,
# after the app's last loopback reading. The subtraction itself is unit-tested in HistoryTest.)
Check ($sent -gt $received -and $loopback -gt 0) `
    "readsb's bytes_out counts more than reached the feed: the MLAT loopback the app subtracts ($loopback bytes)"
if ($sink -and -not $sink.HasExited) { Stop-Process -Id $sink.Id -Force -ErrorAction SilentlyContinue }
Shell "pkill -f 'nc -l -p $sinkPort'" | Out-Null
# Phone feeders are identifiable on the feed itself: readsb sends the UUID (0x1A 0xE4 + 36
# characters) on connect, and every UUID the app creates ends in the phone tag.
$uuid = ((Shell "run-as $pkg cat files/settings.json") | ConvertFrom-Json).uuid
$onWire = [int]((Shell "grep -a -c '$uuid' /data/local/tmp/sink.bin").Trim())
Check ($uuid -like '*-????ad5bce11' -and $onWire -gt 0) "feed carries the phone-tagged feeder UUID ($uuid)"
$runLog = Shell "run-as $pkg cat files/readsb/readsb.log"
Check ($runLog -notmatch [regex]::Escape($uuid)) 'feeder ID not in the readsb log (it goes to readsb in a file)'

# What the stand-in MLAT server received.
$deadline = (Get-Date).AddSeconds(30)
while (-not (Test-Path $mlatSummary) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 1 }
if ($mlatServer -and -not $mlatServer.HasExited) { Stop-Process -Id $mlatServer.Id -Force -ErrorAction SilentlyContinue }
$m = if (Test-Path $mlatSummary) { Get-Content $mlatSummary -Raw | ConvertFrom-Json } else { $null }
$h = $m.handshake
Check ($null -ne $h) 'MLAT handshake received by the server'
Check ($h.user -eq 'smoke-test' -and $h.uuid -eq $uuid) "handshake carries the feeder name and UUID ($($h.user))"
Check ($h.privacy -eq $false) 'handshake has privacy off (the default, as on ADS-B Exchange''s own feeders)'
Check ([math]::Abs($h.lat - $rx.lat) -lt 1e-6 -and [math]::Abs($h.lon - $rx.lon) -lt 1e-6 -and $h.alt -eq 100) `
    "handshake has the receiver position ($($h.lat), $($h.lon), $($h.alt) m)"
Check ($h.clock_type -eq 'dump1090' -and $h.clock_frequency -eq 12000000 -and $h.version -eq 3) 'handshake clock and protocol fields'
Check ($m.input_connected -and $m.clock_reset) 'client reported input connected and clock reset'
$adsbHex = @($expected.aircraft | ForEach-Object { $_.hex })
Check ($m.syncs -gt 0 -and @($m.sync_addresses | Where-Object { $_ -notin $adsbHex }).Count -eq 0) `
    "sync pairs sent for ADS-B aircraft ($($m.syncs) pairs, $(@($m.sync_addresses).Count) aircraft)"
Check ($m.mlat -gt 0 -and (@($m.mlat_addresses) -join ',') -eq $expected.modes_only.hex) `
    "MLAT messages sent for the Mode S-only aircraft ($($m.mlat) messages from $(@($m.mlat_addresses) -join ','))"

# What the stand-in stats endpoint received: the stats package's uploads, every 5 s.
if ($statsServer -and -not $statsServer.HasExited) { Stop-Process -Id $statsServer.Id -Force -ErrorAction SilentlyContinue }
$sm = if (Test-Path $statsSummary) { Get-Content $statsSummary -Raw | ConvertFrom-Json } else { $null }
Check ($sm -and $sm.uploads -ge 5 -and $sm.errors -eq 0) "stats uploads reached the stand-in ($($sm.uploads) uploads, $($sm.errors) bad)"
Check ((@($sm.headerUuids) -join ',') -eq $uuid -and (@($sm.bodyUuids) -join ',') -eq $uuid) `
    'stats uploads carry the feeder UUID, in the header and in the body'
Check ($sm.gzipHeader -and $sm.formType) 'stats uploads have the headers adsbexchange-stats sends'
Check ($sm.maxAircraft -ge 5) "stats uploads carry the aircraft list (up to $($sm.maxAircraft) aircraft in one)"
Check ($sm.anyRange) 'with MLAT sending the position, the receiver distances go too'
Check ($null -eq $m.error) "fake MLAT server ran without errors $(if ($m.error) { "($($m.error))" })"
# The MLAT sync line (every 10 minutes for real; every 5 s against the stand-in server).
$syncLine = ($runLog -split "`n" | Where-Object { $_ -match 'mlat: synced with 7 nearby feeders.*sync pairs' } | Select-Object -First 1)
Check ([bool]$syncLine) "MLAT sync quality logged $(if ($syncLine) { "($(($syncLine -replace '^.*\[feeder\] ', '').Trim()))" })"

# --- 6. compare decode ------------------------------------------------------------------
Step 'Comparing decoded aircraft'
foreach ($f in 'readsb/json/aircraft.json', 'readsb/json/stats.json', 'readsb/readsb.log') {
    $local = Join-Path $out (Split-Path $f -Leaf)
    Shell "run-as $pkg cat files/$f" | Set-Content -Encoding utf8 $local
}
# The log is shown in the app and goes out with "Share diagnostics": no receiver coordinates in it.
$logText = Get-Content (Join-Path $out 'readsb.log') -Raw
$coordLine = [regex]::Match($logText, '(?im)^.*(--lat|--lon|\blat\b\W{0,3}-?\d+\.\d|location:\s*-?\d+\.\d|-76\.0).*$').Value
Check (-not $coordLine) "readsb log has no receiver coordinates $(if ($coordLine) { "($coordLine)" })"

$aircraft = (Get-Content (Join-Path $out 'aircraft.json') -Raw | ConvertFrom-Json).aircraft
$rows = foreach ($e in $expected.aircraft) {
    $a = $aircraft | Where-Object { $_.hex -eq $e.hex } | Select-Object -First 1
    $checks = [ordered]@{
        found    = [bool]$a
        flight   = $a -and ($a.flight -as [string]).Trim() -eq $e.flight
        alt_baro = $a -and $a.alt_baro -eq $e.alt_baro
        position = $a -and $null -ne $a.lat -and [math]::Abs($a.lat - $e.lat) -lt 0.001 -and [math]::Abs($a.lon - $e.lon) -lt 0.001
        gs       = $a -and $null -ne $a.gs -and [math]::Abs($a.gs - $e.gs) -lt 1
        track    = $a -and $null -ne $a.track -and [math]::Abs($a.track - $e.track) -lt 1
        rate     = $a -and $a.baro_rate -eq $e.baro_rate
    }
    $ok = -not ($checks.Values -contains $false)
    if (-not $ok) { $failures++ }
    [pscustomobject]@{
        hex = $e.hex; result = $(if ($ok) { 'PASS' } else { 'FAIL: ' + (($checks.GetEnumerator() | Where-Object { -not $_.Value } | ForEach-Object Key) -join ',') })
        flight = $a.flight; alt = $a.alt_baro; lat = $a.lat; lon = $a.lon; gs = $a.gs; track = $a.track; rate = $a.baro_rate
    }
}
$rows | Format-Table -AutoSize | Out-String | Write-Host
$mo = $aircraft | Where-Object { $_.hex -eq $expected.modes_only.hex } | Select-Object -First 1
Check ($mo -and $mo.alt_baro -eq $expected.modes_only.alt_baro) "Mode S-only aircraft decoded ($($expected.modes_only.hex) at $($mo.alt_baro) ft)"

# --- 6b. lifetime history ---------------------------------------------------------------
Step 'History'
$hist = (Shell "run-as $pkg cat files/history.json") | ConvertFrom-Json
$wantAircraft = @($expected.aircraft).Count + 1 # plus the Mode S-only one
Check ($hist -and $hist.messages -gt 0 -and $hist.messages -le $stats.total.messages_valid) `
    "history counted the messages ($($hist.messages) of readsb's $($stats.total.messages_valid))"
Check ($hist.uniqueAircraft -eq $wantAircraft) "history counted every aircraft once ($($hist.uniqueAircraft) of $wantAircraft)"
Check ($hist.feedingMs -ge 10000 -and $hist.firstFedMs -gt 0) "history counted the time feeding ($([int]($hist.feedingMs / 1000)) s)"
Check ($hist.bytesSent -gt 0 -and $hist.bytesSent -lt $sent) "history's data sent leaves out the loopback ($($hist.bytesSent) bytes, readsb's bytes_out $sent)"
Check ($hist.mlatSyncs -gt 0 -and $hist.mlatMessages -gt 0) "history counted MLAT ($($hist.mlatSyncs) sync pairs, $($hist.mlatMessages) messages)"
Check (TapText 'History') 'opened the History tab'
Start-Sleep -Seconds 2
$historyUi = UiXml
Check ($historyUi -match "text=""$wantAircraft""" -and $historyUi -match 'text="different aircraft"') 'History tab shows the aircraft count'
Screenshot 'history.png'
Check (@($hist.rangeNm | Where-Object { $_ -gt 0 }).Count -ge 3) `
    "range plot recorded the directions aircraft were heard in ($(@($hist.rangeNm | Where-Object { $_ -gt 0 }).Count) of $(@($hist.rangeNm).Count) sectors)"
Check ($historyUi -match 'text="Milestones"' -and $historyUi -match 'text="First up: 10,000 messages"') 'History tab shows the milestones'
$rangeUi = ''
for ($i = 0; $i -lt 6 -and $rangeUi -notmatch 'text="Reception range"'; $i++) {
    Shell 'input swipe 540 1600 540 900 500' | Out-Null; Start-Sleep -Seconds 1; $rangeUi = UiXml
}
Check ($rangeUi -match 'text="Reception range"' -and $rangeUi -notmatch 'Leaderboard') 'History tab shows the range plot, and no Leaderboard link'
Shell 'input swipe 540 1100 540 1500 300' | Out-Null
Screenshot 'history-range.png'
1..4 | ForEach-Object { Shell 'input swipe 540 600 540 1800 300' | Out-Null }
TapText 'Status' | Out-Null
# --- 7. Stop button: readsb should shut down cleanly and not be restarted ---------------
if (-not $SkipStopTest) {
    Step 'Stop button'
    Shell 'logcat -c' | Out-Null
    StartSmokeRun
    Start-Sleep -Seconds 6

    # The battery running down on a charger raises the charger warning. The emulator's battery is
    # on AC; stop it charging, let the service see that (it checks every 10 s), drop the level
    # 4 points, then put it back.
    Step 'Charger warning'
    $level = [int]([regex]::Match((Shell 'dumpsys battery'), 'level: (\d+)').Groups[1].Value)
    Adb emu power status not-charging | Out-Null
    Start-Sleep -Seconds 12
    Adb emu power capacity ($level - 4) | Out-Null
    Start-Sleep -Seconds 12
    $warned = ((Shell "run-as $pkg cat files/feeder_status.json") | ConvertFrom-Json).chargeProblem
    Check ($warned -eq $true) "battery dropping on a charger that isn't charging raises the warning ($level% -> $($level - 4)%)"
    $healthUi = ''
    for ($i = 0; $i -lt 6 -and $healthUi -notmatch 'text="Not charging, and the battery is running down'; $i++) {
        Shell 'input swipe 540 1600 540 900 500' | Out-Null; Start-Sleep -Seconds 1; $healthUi = UiXml
    }
    Check ($healthUi -match 'text="Not charging, and the battery is running down' -and $healthUi -match 'on a charger, not charging') `
        'Phone health says the charger isn''t charging, with the warning'
    Screenshot 'charger-warning.png'
    Adb emu power status charging | Out-Null
    Adb emu power capacity $level | Out-Null
    Start-Sleep -Seconds 12
    $warned = ((Shell "run-as $pkg cat files/feeder_status.json") | ConvertFrom-Json).chargeProblem
    Check ($warned -eq $false) 'the warning clears once it charges again'
    1..4 | ForEach-Object { Shell 'input swipe 540 600 540 1800 300' | Out-Null }

    # Screen-only work stops while nobody is looking: with the app in the background, readsb keeps
    # running but stops rewriting aircraft.json every second; it resumes when the app comes back.
    Step 'No screen-only work in the background'
    function JsonMtime { [long]((Shell "run-as $pkg stat -c %Y files/readsb/json/aircraft.json").Trim()) }
    Shell 'input keyevent KEYCODE_HOME' | Out-Null
    Start-Sleep -Seconds 4 # the service notices within one 2 s tick
    # In the background readsb writes it once a minute (for the history), not every second.
    $mtimes = foreach ($i in 1..8) { JsonMtime; Start-Sleep -Seconds 1 }
    $changes = @($mtimes | Select-Object -Unique).Count - 1
    $m2 = $mtimes[-1]
    Check ($changes -le 1) "aircraft.json at most once a minute while the app is in the background ($changes changes in 8 s)"
    Check ([bool](Shell "pidof $pkg`:feeder").Trim() -and -not (ReadsbExited)) 'readsb still running in the background'
    Shell "am start -n $activity" | Out-Null
    Start-Sleep -Seconds 4
    $m3 = JsonMtime
    Check ($m3 -gt $m2) "aircraft.json updates again once the app is back (mtime $m2 -> $m3)"
    Check (UiHas 'text="Stop"') 'status shows the running receiver after coming back'
    Check (TapText 'Stop') 'tapped Stop'
    $t0 = Get-Date
    while (-not (ReadsbExited) -and ((Get-Date) - $t0).TotalSeconds -lt 10) { Start-Sleep -Milliseconds 200 }
    $stopSecs = ((Get-Date) - $t0).TotalSeconds
    Start-Sleep -Seconds 3 # give a (wrong) sticky restart time to show up
    $log = Shell "run-as $pkg cat files/readsb/readsb.log"
    $normal = $log -match 'Normal exit' -and $log -match 'readsb exited with code 0'
    $restarted = (Shell 'logcat -d') -match "restart of crashed service $pkg"
    Check ($normal -and -not $restarted) ("readsb exited normally {0:N1} s after Stop, no restart" -f $stopSecs)
    if (-not $normal -or $restarted) {
        Shell 'logcat -d' | Set-Content -Encoding utf8 (Join-Path $out 'stop-test-logcat.txt')
        $log | Set-Content -Encoding utf8 (Join-Path $out 'stop-test-readsb.log')
    }
    Start-Sleep -Seconds 2
    Check (UiHas 'text="(Feeding is paused|Plug in your SDR|This phone can.{1,6}t use an SDR)"') 'status card shows the stopped state'
    Screenshot 'stopped.png'
}

# --- 8. Battery protection: pause on a low battery, resume when charging -------------------
if (-not $SkipStopTest) {
    Step 'Battery protection'
    function FeederStatus { (Shell "run-as $pkg cat files/feeder_status.json") | ConvertFrom-Json }
    Shell 'logcat -c' | Out-Null
    StartSmokeRun
    Start-Sleep -Seconds 6
    $level = [int]([regex]::Match((Shell 'dumpsys battery'), 'level: (\d+)').Groups[1].Value)
    Adb emu power ac off | Out-Null
    Adb emu power status discharging | Out-Null
    Adb emu power capacity 9 | Out-Null
    # The service checks every 10 s. Pausing stops readsb, which ends the :feeder process; Android
    # restarts the service, which finds the hold and waits in the foreground without readsb.
    $t0 = Get-Date
    while (-not ((ReadsbExited) -and (Shell 'logcat -d') -match "restart of crashed service $pkg") -and ((Get-Date) - $t0).TotalSeconds -lt 30) {
        Start-Sleep -Seconds 1
    }
    Start-Sleep -Seconds 3
    $st = FeederStatus
    Check ($st.state -eq 'PAUSED' -and $st.reason -eq 'LOW_BATTERY') "9% on battery pauses feeding ($($st.state) $($st.reason))"
    Check ((ReadsbExited) -and (Shell "run-as $pkg cat files/feeder_hold").Trim() -eq 'LOW_BATTERY') 'readsb stopped, and the pause is kept for the restarted service'
    Check ([bool](Shell "pidof $pkg`:feeder").Trim() -and (Shell 'dumpsys activity services FeederService') -match 'isForeground=true') `
        'the restarted service waits in the foreground'
    Start-Sleep -Seconds 2
    Check (UiHas 'text="Paused: battery low"') 'status card says paused: battery low'
    Screenshot 'paused-low-battery.png'

    # Charging again: the waiting service resumes. On the emulator that means looking for the SDR,
    # which isn't there, so it ends at "no SDR"; on a phone readsb would start.
    Adb emu power ac on | Out-Null
    Adb emu power status charging | Out-Null
    Adb emu power capacity $level | Out-Null
    $t0 = Get-Date
    do { Start-Sleep -Seconds 1; $st = FeederStatus } while ($st.state -eq 'PAUSED' -and ((Get-Date) - $t0).TotalSeconds -lt 20)
    $held = Shell "run-as $pkg ls files/feeder_hold 2>/dev/null"
    $resumedLog = (Shell "run-as $pkg cat files/readsb/readsb.log") -match 'resuming after the low_battery pause'
    Check ($st.reason -eq 'NO_SDR' -and -not $held -and $resumedLog) "charging resumes: hold cleared, service looked for the SDR ($($st.state) $($st.reason))"
}

# --- 9. Power test plumbing: a run in the NO_WAKE_LOCK phase feeds without the wake lock -----
# The phases themselves (18 minutes each, restarts in between) need a real SDR and are unit
# tested; here a test is planted at its second phase and a capture run checks it's applied.
if (-not $SkipStopTest) {
    Step 'Power test settings'
    $test = Join-Path $out 'power_test.json'
    '{"startedMs":1,"plan":["BASELINE","NO_WAKE_LOCK","BASELINE"],' +
        '"phases":[{"variant":"BASELINE"},{"variant":"NO_WAKE_LOCK"}],"finishedMs":null,"stopped":null}' |
        Set-Content -Encoding ascii -NoNewline $test
    Adb push $test /data/local/tmp/power_test.json | Out-Null
    Shell "cat /data/local/tmp/power_test.json | run-as $pkg sh -c 'cat > files/power_test.json'; rm /data/local/tmp/power_test.json" | Out-Null
    $sink2 = Start-Process -FilePath $adb -ArgumentList @('-s', $serial, 'shell', 'nc -l -p 30101 > /dev/null') -WindowStyle Hidden -PassThru
    Start-Sleep -Seconds 1
    StartSmokeRun '127.0.0.1,30101'
    Start-Sleep -Seconds 8
    $log = Shell "run-as $pkg cat files/readsb/readsb.log"
    Check ($log -match 'power test: phase 2 of 3, no_wake_lock' -and $log -match 'power test: wake lock not held' -and
           -not (WakeLockListed)) 'power test phase 2 feeds without the wake lock'
    Check (TapText 'Stop') 'tapped Stop'
    # Start again at once, while readsb is still shutting down. Its process can't start readsb a
    # second time, so the service lets that process end and starts in the one Android restarts it
    # in. The restart has no capture file, so on the emulator it ends at "no SDR" (as after the
    # battery pause); before the fix it ended at "couldn't start".
    $stillRunning = -not (ReadsbExited)
    StartSmokeRun
    Start-Sleep -Seconds 3
    $pt = (Shell "run-as $pkg cat files/power_test.json") | ConvertFrom-Json
    Check ($pt.stopped -match 'user_stopped') "Stop ends the power test, keeping its results ($($pt.stopped))"
    $t0 = Get-Date
    do { Start-Sleep -Seconds 1; $st = FeederStatus } while ($st.reason -notin 'NO_SDR', 'START_FAILED' -and ((Get-Date) - $t0).TotalSeconds -lt 30)
    $waited = (Shell "run-as $pkg cat files/readsb/readsb.log") -match 'starting again in a new process'
    Check ($stillRunning -and $waited -and $st.reason -eq 'NO_SDR') `
        "Start right after Stop starts again in a new process ($($st.state) $($st.reason), readsb was still running: $stillRunning)"
    Shell "run-as $pkg rm -f files/power_test.json" | Out-Null
    if (-not $sink2.HasExited) { Stop-Process -Id $sink2.Id -ErrorAction SilentlyContinue }
}

# --- 10. Mobile data: feeding pauses off Wi-Fi and resumes back on it -----------------------
# With the emulator's Wi-Fi off its default network is (metered) cellular. "Feed over mobile
# data" is off by default, so after 30 s on mobile data feeding pauses; back on Wi-Fi it resumes
# (on the emulator that ends at "no SDR", as for the battery pause).
if (-not $SkipStopTest) {
    Step 'Mobile data pause'
    function FeederStatus { (Shell "run-as $pkg cat files/feeder_status.json") | ConvertFrom-Json }
    $sink3 = Start-Process -FilePath $adb -ArgumentList @('-s', $serial, 'shell', 'nc -l -p 30102 > /dev/null') -WindowStyle Hidden -PassThru
    Start-Sleep -Seconds 1
    StartSmokeRun '127.0.0.1,30102'
    Start-Sleep -Seconds 4
    try {
        Shell 'svc wifi disable' | Out-Null
        $t0 = Get-Date
        do { Start-Sleep -Seconds 2; $st = FeederStatus } while ($st.state -ne 'PAUSED' -and ((Get-Date) - $t0).TotalSeconds -lt 60)
        $secs = [int]((Get-Date) - $t0).TotalSeconds
        Check ($st.state -eq 'PAUSED' -and $st.reason -eq 'MOBILE_DATA') "on mobile data, feeding pauses ($($st.state) $($st.reason) after $secs s)"
        Start-Sleep -Seconds 4 # the restarted service waits; let the screen catch up
        Check (UiHas 'text="Paused: not on Wi-Fi"') 'status card says paused: not on Wi-Fi'
        Screenshot 'paused-mobile-data.png'
    } finally {
        Shell 'svc wifi enable' | Out-Null
    }
    $t0 = Get-Date
    do { Start-Sleep -Seconds 2; $st = FeederStatus } while ($st.state -eq 'PAUSED' -and ((Get-Date) - $t0).TotalSeconds -lt 40)
    $held = Shell "run-as $pkg ls files/feeder_hold 2>/dev/null"
    $resumedLog = (Shell "run-as $pkg cat files/readsb/readsb.log") -match 'resuming after the mobile_data pause'
    Check ($st.reason -eq 'NO_SDR' -and -not $held -and $resumedLog) "back on Wi-Fi it resumes: hold cleared, service looked for the SDR ($($st.state) $($st.reason))"
    if (-not $sink3.HasExited) { Stop-Process -Id $sink3.Id -ErrorAction SilentlyContinue }
}

$crashes = Shell 'logcat -d -b crash'
Check (-not ($crashes -match $pkg)) 'no app crashes'
if ($crashes -match $pkg) { Write-Host $crashes }
Write-Host "Artifacts in $out (screenshots, aircraft.json, stats.json, readsb.log)"


if (-not $KeepEmulator -and $startedEmulator) { Adb emu kill | Out-Null }

if ($failures) { Write-Host "SMOKE TEST FAILED ($failures problems)" -ForegroundColor Red; exit 1 }
Write-Host "SMOKE TEST PASSED" -ForegroundColor Green
