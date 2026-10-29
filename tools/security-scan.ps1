<#
.SYNOPSIS
    The pre-release scans: secrets, known vulnerabilities, static analysis and an SBOM.

.DESCRIPTION
    Runs each scanner in a throwaway container with the repository mounted read-only. Nothing is
    built or installed (a scan should never build or install the application it assesses):
    dependencies are read from the Gradle lockfiles, the C libraries from the commits
    pinned in app/src/main/cpp/cmake/deps.cmake.

      Secrets          gitleaks, over every commit and over the working tree
      Dependencies     osv-scanner on the lockfiles; OSV's API for the pinned C library commits
      Static analysis  semgrep (Kotlin, Java, C, secrets), on this project's code only
      SBOM             syft (CycloneDX), plus the C libraries, which no scanner can identify

    Writes security/sbom.cdx.json and security/scan-summary.md (to attach to a release
    review) and the raw scanner output to build/security-scan. Exits 1 if there is anything to
    act on: a secret, a known vulnerability in a library that ships in the APK, or an open
    static-analysis finding. Vulnerabilities in build tools (lint, the Android Gradle plugin)
    are listed but don't fail the scan, since they never reach a phone.

    Needs Docker. semgrep downloads its rules from semgrep.dev and runs with telemetry off; the
    two OSV lookups send package names and versions (or commit hashes) to osv.dev.

.EXAMPLE
    pwsh -File tools\security-scan.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$raw = [IO.Path]::Combine($root, 'build', 'security-scan')
$committed = Join-Path $root 'security'
New-Item -ItemType Directory -Force $raw, $committed | Out-Null

# Pinned by digest. To update one: docker pull <name>:latest, then copy its RepoDigest here.
$images = [ordered]@{
    gitleaks = @{ ref = 'zricethezav/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f'; version = '8.30.1' }
    osv      = @{ ref = 'ghcr.io/google/osv-scanner@sha256:afd838850ac1a0fcc15ff4a041dc9ba11123c3f0d2666217a5f0fcf9222b55fa'; version = '2.6.0' }
    semgrep  = @{ ref = 'semgrep/semgrep@sha256:acaac22ffc7b7cc5926de0751b223bce0b2491c33d18422fa72f632c78d81198'; version = '1.177.0' }
    syft     = @{ ref = 'anchore/syft@sha256:500e2d872ac019436926e8322b4fc1f39441d94d21f6f4046c6ff29b30e8cb02'; version = '1.52.0' }
}
$mount = @('-v', "${root}:/repo:ro", '-v', "${raw}:/out")
$blocking = [System.Collections.Generic.List[string]]::new()

function Step($text) { Write-Host "`n== $text" -ForegroundColor Cyan }
function Run([string[]] $dockerArgs) {
    & docker run --rm @dockerArgs 2>&1 | ForEach-Object { Write-Verbose "$_" }
    $LASTEXITCODE
}
function ReadJson($name) {
    $f = Join-Path $raw $name
    if (Test-Path $f) { Get-Content $f -Raw | ConvertFrom-Json } else { $null }
}

# --- secrets ----------------------------------------------------------------------------------
Step 'Secrets (gitleaks)'
$gitleaksArgs = @('--config', '/repo/.gitleaks.toml', '--redact', '--no-banner', '--report-format', 'json')
Run (@('--network', 'none') + $mount + @($images.gitleaks.ref, 'git', '/repo') + $gitleaksArgs + @('--report-path', '/out/gitleaks-history.json')) | Out-Null
Run (@('--network', 'none') + $mount + @($images.gitleaks.ref, 'dir', '/repo') + $gitleaksArgs + @('--report-path', '/out/gitleaks-worktree.json')) | Out-Null
$commits = [int](git -C $root rev-list --all --count)
$leaks = @(ReadJson 'gitleaks-history.json') + @(ReadJson 'gitleaks-worktree.json') | Where-Object { $_ }
Write-Host "  $commits commits and the working tree: $($leaks.Count) finding(s)"
foreach ($l in $leaks) { $blocking.Add("Secret: $($l.RuleID) in $($l.File -replace '^/repo/','') line $($l.StartLine)") }

# --- dependencies -----------------------------------------------------------------------------
Step 'Dependencies (osv-scanner, from the lockfiles)'
Run ($mount + @($images.osv.ref, 'scan', 'source', '-L', '/repo/app/gradle.lockfile', '-L', '/repo/buildscript-gradle.lockfile',
        '--format', 'json', '--output', '/out/osv.json')) | Out-Null
$osv = ReadJson 'osv.json'
if (-not $osv) { throw 'osv-scanner produced no report' }

# Every locked package ("group:name:version") and whether it ships: the lockfiles record which
# Gradle configurations use it, and only (debug|release)RuntimeClasspath goes into the APK.
$locked = @{}
foreach ($lockfile in 'app/gradle.lockfile','buildscript-gradle.lockfile') {
    foreach ($line in Get-Content (Join-Path $root $lockfile)) {
        if ($line -match '^([^#=][^=]*:[^:=]+)=(.+)$') {
            $key, $configurations = $Matches[1], ($Matches[2] -split ',')
            $ships = [bool]($configurations -match '^(debug|release)RuntimeClasspath$')
            $locked[$key] = $locked[$key] -or $ships
        }
    }
}
$shippedPackages = @{}
foreach ($k in $locked.Keys) { if ($locked[$k]) { $shippedPackages[($k -replace ':[^:]+$', '')] = $true } }
# osv-scanner only reports the vulnerable packages; this is how many it checked.
$packageCount = $locked.Count
$vulnRows = @{}
foreach ($res in $osv.results) {
    foreach ($p in $res.packages) {
        $key = "$($p.package.name) $($p.package.version)"
        if ($vulnRows.ContainsKey($key)) { continue }
        $vulnRows[$key] = [pscustomobject]@{
            Package = $key
            Shipped = [bool]$shippedPackages[$p.package.name]
            Ids     = ($p.vulnerabilities | ForEach-Object { $_.id }) -join ', '
            Summary = ($p.vulnerabilities | ForEach-Object { $_.summary } | Where-Object { $_ } | Select-Object -Unique) -join '; '
            MaxCvss = (($p.groups | ForEach-Object { [double]($_.max_severity -as [double]) }) | Measure-Object -Maximum).Maximum
        }
    }
}
$vulns = $vulnRows.Values | Sort-Object @{ e = 'Shipped'; Descending = $true }, @{ e = 'MaxCvss'; Descending = $true }
$shippedVulns = @($vulns | Where-Object Shipped)
Write-Host "  $packageCount packages: $($shippedVulns.Count) vulnerable in the APK, $(@($vulns).Count - $shippedVulns.Count) in build tools only"
foreach ($v in $shippedVulns) { $blocking.Add("Vulnerable library in the APK: $($v.Package) ($($v.Ids))") }

Step 'C libraries (OSV, by pinned commit)'
$native = foreach ($line in Get-Content (Join-Path $root 'app/src/main/cpp/cmake/deps.cmake')) {
    if ($line -match '^fetch_dep\((\S+)\s+(\S+)\s+([0-9a-f]{40})\)\s*#\s*(.*)$') {
        [pscustomobject]@{ Name = $Matches[1]; Url = $Matches[2]; Commit = $Matches[3]; Note = $Matches[4].Trim() }
    }
}
foreach ($n in $native) {
    $r = Invoke-RestMethod -Method Post -Uri 'https://api.osv.dev/v1/query' -ContentType 'application/json' `
        -Body (@{ commit = $n.Commit } | ConvertTo-Json) -TimeoutSec 60
    $ids = @($r.vulns | Where-Object { $_ } | ForEach-Object { $_.id })
    $n | Add-Member Advisories ($ids -join ', ')
    Write-Host ("  {0,-7} {1}  {2}" -f $n.Name, $n.Commit.Substring(0, 10), $(if ($ids) { $ids -join ', ' } else { 'no known advisories' }))
    foreach ($id in $ids) { $blocking.Add("Vulnerable C library: $($n.Name) @ $($n.Commit.Substring(0, 10)) ($id)") }
}

# --- static analysis --------------------------------------------------------------------------
Step 'Static analysis (semgrep)'
# semgrep's image insists on its code being mounted at /src.
Run (@('-v', "${root}:/src:ro", '-v', "${raw}:/out", '-w', '/src', $images.semgrep.ref, 'semgrep', 'scan', '--metrics=off',
        '--config', 'p/default', '--config', 'p/kotlin', '--config', 'p/java', '--config', 'p/c', '--config', 'p/secrets',
        '--exclude', 'app/src/main/cpp/third_party', '--exclude', 'build', '--exclude', '.cxx', '--exclude', '.gradle', '--exclude', '.kotlin', '--exclude', '.claude',
        '--json', '--output', '/out/semgrep.json')) | Out-Null
$semgrep = ReadJson 'semgrep.json'
if (-not $semgrep) { throw 'semgrep produced no report' }
$findings = @($semgrep.results)
$suppressed = (Select-String -Path (git -C $root ls-files | ForEach-Object { Join-Path $root $_ }) -Pattern 'nosemgrep' -SimpleMatch |
    Where-Object { $_.Path -notmatch '(security-scan\.ps1|\.md)$' }).Count
$partial = @($semgrep.errors | ForEach-Object { $_.path } | Where-Object { $_ } | Sort-Object -Unique)
Write-Host "  $($semgrep.paths.scanned.Count) files: $($findings.Count) open finding(s), $suppressed reviewed and annotated in the code"
foreach ($f in $findings) { $blocking.Add("Static analysis: $($f.check_id -replace '.*\.','') at $($f.path):$($f.start.line)") }

# --- SBOM -------------------------------------------------------------------------------------
Step 'Software Bill of Materials (syft + the C libraries)'
# One argument each: PowerShell on Linux expands a separate "./build/**" against the file system.
Run (@('--network', 'none') + $mount + @($images.syft.ref, 'scan', 'dir:/repo',
        '--exclude=./app/src/main/cpp/third_party/**', '--exclude=./**/build/**', '--exclude=./app/.cxx/**', '--exclude=./.gradle/**', '--exclude=./.claude/**',
        '-o', 'cyclonedx-json=/out/sbom-syft.cdx.json')) | Out-Null
$sbom = ReadJson 'sbom-syft.cdx.json'
if (-not $sbom) { throw 'syft produced no SBOM' }
# syft reads app/gradle.lockfile but not buildscript-gradle.lockfile (the build's own plugins), so
# add what it missed. Mark each package "required" if it ships in the APK and "excluded" if only
# the build uses it (CycloneDX: excluded = not part of what is delivered).
$seen = @{}
foreach ($c in @($sbom.components)) {
    if ($c.purl -match '^pkg:maven/([^/]+)/([^@]+)@([^?]+)') {
        $key = "$($Matches[1]):$($Matches[2]):$($Matches[3])"
        $seen[$key] = $true
        if ($locked.ContainsKey($key)) { $c | Add-Member -Force scope $(if ($locked[$key]) { 'required' } else { 'excluded' }) }
    }
}
$missing = foreach ($key in $locked.Keys | Where-Object { -not $seen[$_] } | Sort-Object) {
    $g, $n, $v = $key -split ':'
    [ordered]@{
        'bom-ref' = "pkg:maven/$g/$n@$v"; type = 'library'; group = $g; name = $n; version = $v
        purl = "pkg:maven/$g/$n@$v"; scope = $(if ($locked[$key]) { 'required' } else { 'excluded' })
    }
}
$sbom.components = @($sbom.components) + @($missing) + @($native | ForEach-Object {
        $gh = [regex]::Match($_.Url, 'github\.com/([^/]+)/([^/.]+)')
        [ordered]@{
            'bom-ref'          = "native:$($_.Name)"
            type               = 'library'
            scope              = 'required'
            name               = $gh.Groups[2].Value
            version            = $_.Commit
            description        = "C library compiled into libphonereceiver.so ($($_.Note))"
            purl               = "pkg:github/$($gh.Groups[1].Value)/$($gh.Groups[2].Value)@$($_.Commit)"
            externalReferences = @(@{ type = 'vcs'; url = $_.Url })
        }
    })
$sbom | ConvertTo-Json -Depth 30 | Set-Content -Encoding utf8 (Join-Path $committed 'sbom.cdx.json')
$inApk = @($sbom.components | Where-Object { $_.scope -eq 'required' }).Count
Write-Host "  $(@($sbom.components).Count) components ($inApk in the APK) -> security/sbom.cdx.json"

# --- summary ----------------------------------------------------------------------------------
$head = git -C $root rev-parse --short HEAD
$dirty = if (git -C $root status --porcelain -- . ':!security') { ' plus uncommitted changes' } else { '' }
$md = [System.Text.StringBuilder]::new()
function Line($s = '') { [void]$md.AppendLine($s) }
Line '# Security scan summary'
Line
Line "Generated by ``tools/security-scan.ps1`` on $(Get-Date -Format 'yyyy-MM-dd HH:mm zzz') at commit ``$head``$dirty."
Line 'Every scanner ran in a container with the repository mounted read-only; nothing was built or installed.'
Line
Line '| Check (policy section 10) | Tool | Result |'
Line '|---|---|---|'
Line "| Secret scanning across full history | gitleaks $($images.gitleaks.version) | $commits commits and the working tree: **$($leaks.Count) findings** |"
Line "| Dependency vulnerability scan from the lockfile | osv-scanner $($images.osv.version) | $packageCount packages: **$($shippedVulns.Count) vulnerable in the APK**, $(@($vulns).Count - $shippedVulns.Count) in build tools only |"
Line "| Dependency vulnerability scan, C libraries | OSV API, by commit | $(@($native | Where-Object Advisories).Count) of $(@($native).Count) with known advisories (see note) |"
Line "| Static application security testing | semgrep $($images.semgrep.version) | $($semgrep.paths.scanned.Count) files: **$($findings.Count) open findings**, $suppressed reviewed and annotated in the code |"
Line "| Software Bill of Materials | syft $($images.syft.version) | ``security/sbom.cdx.json``, $(@($sbom.components).Count) components, $inApk of them in the APK (CycloneDX) |"
Line '| Configuration and container scanning | - | Not applicable: nothing is containerised |'
Line '| Agent configuration review | - | **Unmeasured**: no approved marketplace exists to compare against. Declared at review instead |'
Line
if (@($vulns).Count) {
    Line '## Known vulnerabilities'
    Line
    Line '| Package | Where | Advisories | Max CVSS | Summary |'
    Line '|---|---|---|---|---|'
    foreach ($v in $vulns) { Line "| $($v.Package) | $(if ($v.Shipped) { '**APK**' } else { 'build tools' }) | $($v.Ids) | $($v.MaxCvss) | $($v.Summary) |" }
    Line
    Line '"Build tools" means the package is only used by the build (the Android lint tool or the'
    Line 'Android Gradle and Kotlin plugins), per the configurations recorded in the lockfiles. It never'
    Line 'reaches a phone, but it does run on the build machine.'
    Line
}
Line '## C libraries'
Line
Line '| Library | Pinned commit | Upstream | Known advisories |'
Line '|---|---|---|---|'
foreach ($n in $native) { Line "| $($n.Name) ($($n.Note)) | ``$($n.Commit)`` | $($n.Url) | $(if ($n.Advisories) { $n.Advisories } else { 'none' }) |" }
Line
Line 'OSV answers "no known advisories" both for a clean commit and for a repository it does not'
Line 'track, so for readsb and rtl-sdr-blog in particular this is weak evidence.'
Line
if ($findings.Count) {
    Line '## Open static-analysis findings'
    Line
    foreach ($f in $findings) { Line "- ``$($f.path):$($f.start.line)`` $($f.check_id): $(($f.extra.message -replace '\s+', ' ').Trim())" }
    Line
}
if ($partial.Count) {
    Line '## Files semgrep could only partly parse'
    Line
    foreach ($p in $partial) { Line "- ``$p``" }
    Line
}
Line '## Scanner images'
Line
foreach ($k in $images.Keys) { Line "- $k $($images[$k].version): ``$($images[$k].ref)``" }
$md.ToString() | Set-Content -Encoding utf8 (Join-Path $committed 'scan-summary.md')

Step 'Result'
if ($blocking.Count) {
    $blocking | ForEach-Object { Write-Host "  FAIL $_" -ForegroundColor Red }
    Write-Host "`nSECURITY SCAN: $($blocking.Count) item(s) to act on. See security/scan-summary.md" -ForegroundColor Red
    exit 1
}
Write-Host '  Nothing to act on. See security/scan-summary.md' -ForegroundColor Green
exit 0
