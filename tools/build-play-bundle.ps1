<#
.SYNOPSIS
    Builds the Google Play App Bundle, signed with the upload key, without any typing of paths.

.DESCRIPTION
    Right-click this file and choose "Run with PowerShell".
      1. A window opens: pick the upload key file (.jks).
      2. Type the key's password (it shows as asterisks).
      3. The script checks the key, builds the bundle, checks the bundle's signature, and shows
         the key's SHA-256 fingerprint so it can be compared with the one saved earlier.
    The password is only held by this window while it runs: it isn't saved anywhere, isn't put
    on a command line, and is cleared at the end. Only the key file's location is remembered (in
    %LOCALAPPDATA%\AdsbxFeeder), so the file picker opens in the right folder next time.

    Works in Windows PowerShell 5.1 (the right-click "Run with PowerShell") and PowerShell 7.
#>

# Continue, not Stop: Java prints harmless notices on stderr, which Windows PowerShell would
# otherwise treat as errors. Each step checks exit codes instead.
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$jdk = 'C:\Android\jdk-17'
$keytool = Join-Path $jdk 'bin\keytool.exe'
$jarsigner = Join-Path $jdk 'bin\jarsigner.exe'
$bundle = Join-Path $root 'app\build\outputs\bundle\release\app-release.aab'
$memory = Join-Path $env:LOCALAPPDATA 'AdsbxFeeder\upload-keystore-location.txt'

function Say([string]$text, [string]$color = 'Gray') { Write-Host $text -ForegroundColor $color }
function Finish([int]$code) {
    Write-Host ''
    Read-Host 'Press Enter to close this window' | Out-Null
    exit $code
}

# The file picker needs a single-threaded window; PowerShell 7 isn't, so restart in that mode.
if ([Threading.Thread]::CurrentThread.GetApartmentState() -ne 'STA') {
    $exe = (Get-Process -Id $PID).Path
    & $exe -NoProfile -ExecutionPolicy Bypass -STA -File $PSCommandPath
    exit $LASTEXITCODE
}

Say ''
Say 'ADS-B Exchange Phone Feeder: build the Google Play bundle' 'Cyan'
Say ''

if (-not (Test-Path $keytool)) { Say "Java isn't where the build expects it ($jdk)." 'Red'; Finish 1 }

# --- 1. The key file ------------------------------------------------------------------------
Add-Type -AssemblyName System.Windows.Forms
$picker = New-Object System.Windows.Forms.OpenFileDialog
$picker.Title = 'Choose the upload key file (.jks)'
$picker.Filter = 'Upload key (*.jks; *.keystore)|*.jks;*.keystore|All files (*.*)|*.*'
if (Test-Path $memory) {
    $last = (Get-Content $memory -Raw).Trim()
    if ($last -and (Test-Path $last)) {
        $picker.InitialDirectory = Split-Path $last
        $picker.FileName = Split-Path $last -Leaf
    }
}
Say '1. Choose the upload key file in the window that just opened.'
if ($picker.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { Say 'No file chosen; nothing was done.' 'Yellow'; Finish 1 }
$keystore = $picker.FileName
Say "   $keystore"
if ($keystore.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) {
    Say '   That file is inside the project folder. Move it somewhere else first, so it can never be' 'Red'
    Say '   copied along with the code.' 'Red'
    Finish 1
}
New-Item -ItemType Directory -Force (Split-Path $memory) | Out-Null
Set-Content -Path $memory -Value $keystore

# --- 2. The password, and a check that it opens the key ------------------------------------
Say ''
$secure = Read-Host '2. Type the key''s password (it shows as asterisks), then press Enter' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $env:ADSBX_UPLOAD_KEYSTORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}
# One password for the file and the key (the .jks files keytool makes today require it).
$env:ADSBX_UPLOAD_KEY_PASSWORD = $env:ADSBX_UPLOAD_KEYSTORE_PASSWORD
$env:ADSBX_UPLOAD_KEYSTORE = $keystore

try {
    Say ''
    Say '3. Checking the key...'
    # keytool reads the password from the environment (":env"), never from the command line.
    $list = & $keytool -list -keystore $keystore -storepass:env ADSBX_UPLOAD_KEYSTORE_PASSWORD 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        if ($list -match 'password was incorrect|keystore password') { Say '   The password is wrong. Run this again and retype it.' 'Red' }
        else { Say '   The file could not be opened as a key:'; Say $list 'Red' }
        Finish 1
    }
    $aliases = @([regex]::Matches($list, '(?m)^([^,\r\n]+),\s.*PrivateKeyEntry') | ForEach-Object { $_.Groups[1].Value.Trim() })
    if ($aliases.Count -eq 0) { Say '   That file holds no key to sign with.' 'Red'; Finish 1 }
    $alias = if ($aliases -contains 'upload') { 'upload' } else { $aliases[0] }
    $env:ADSBX_UPLOAD_KEY_ALIAS = $alias
    $details = & $keytool -list -v -keystore $keystore -alias $alias -storepass:env ADSBX_UPLOAD_KEYSTORE_PASSWORD 2>&1 | Out-String
    $keyFingerprint = ([regex]::Match($details, 'SHA256:\s*([0-9A-F:]+)')).Groups[1].Value
    $until = ([regex]::Match($details, 'until:\s*(.+)')).Groups[1].Value.Trim()
    Say "   OK. Key name: $alias. Valid until: $until" 'Green'

    # --- 4. Build ---------------------------------------------------------------------------
    Say ''
    Say '4. Building the bundle. This takes a few minutes; the build''s own messages scroll past.'
    Say ''
    $env:JAVA_HOME = $jdk
    $env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\Android\tmp'
    Remove-Item $bundle -ErrorAction SilentlyContinue
    Push-Location $root
    try { & (Join-Path $root 'gradlew.bat') bundleRelease --console=plain } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $bundle)) {
        Say ''
        Say 'The build failed. Scroll up for the reason, or copy this window''s text to Claude.' 'Red'
        Finish 1
    }

    # --- 5. Check the bundle's signature ------------------------------------------------------
    Say ''
    Say '5. Checking the bundle''s signature...'
    $verify = & $jarsigner -verify $bundle 2>&1 | Out-String
    $printed = & $keytool -printcert -jarfile $bundle 2>&1 | Out-String
    $bundleFingerprint = ([regex]::Match($printed, 'SHA256:\s*([0-9A-F:]+)')).Groups[1].Value
    if ($verify -notmatch 'jar verified' -or -not $bundleFingerprint -or $bundleFingerprint -ne $keyFingerprint) {
        Say '   The bundle is not signed with this key as expected:' 'Red'
        Say $verify 'Red'
        Finish 1
    }
    Say '   Signed with your upload key.' 'Green'
    Say ''
    Say 'Done. The bundle for Google Play is:' 'Cyan'
    Say "   $bundle"
    Say ''
    Say 'Its upload key fingerprint (SHA-256) is below. Compare it with the one you saved:' 'Cyan'
    Say "   $keyFingerprint" 'White'
    Say ''
    Say 'Nothing can use this bundle until the app exists in Play Console. Until then you can delete it.'
    Start-Process explorer.exe "/select,`"$bundle`""
    Finish 0
} finally {
    # Forget the password and the key details as soon as the build is done.
    Remove-Item Env:ADSBX_UPLOAD_KEYSTORE_PASSWORD, Env:ADSBX_UPLOAD_KEY_PASSWORD, Env:ADSBX_UPLOAD_KEYSTORE, Env:ADSBX_UPLOAD_KEY_ALIAS -ErrorAction SilentlyContinue
}
