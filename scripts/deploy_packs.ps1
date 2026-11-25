param(
    [switch]$PackageOnly,
    [switch]$Deploy,
    [string]$Server = '',
    [string]$BaseUrl = '',
    [string[]]$Pack = @(),
    [string]$Version = '1.0.0',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$ExistingWebroot = '',
    [switch]$BootstrapCertbot
)
$ErrorActionPreference = 'Stop'
if ($PackageOnly -and $Deploy) { throw 'Choose -PackageOnly or -Deploy.' }
$taskServerMatch = [regex]::Match($Server, '^([a-z_][a-z0-9_-]*)@([A-Za-z0-9][A-Za-z0-9.-]*)$')
if ($Deploy -and -not $taskServerMatch.Success) { throw '-Deploy requires an explicit user@host in -Server.' }
$taskOwner = if ($Deploy) { $taskServerMatch.Groups[1].Value } else { '' }
if ($Deploy -and ($ExistingWebroot -notmatch '^/[A-Za-z0-9_./-]+$' -or $ExistingWebroot -match '(^|/)\.\.?(/|$)')) { throw '-Deploy requires a normalized -ExistingWebroot already served on port 80.' }
$taskRepo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskNode = (Get-Command node -ErrorAction Stop).Source
$taskArtifacts = Join-Path $taskRepo 'artifacts'
$taskSite = Join-Path $taskArtifacts 'packs-site'
$taskArchive = Join-Path $taskArtifacts 'linguareader-packs-server.tar.gz'
$taskOldJava = $env:JAVA_HOME
try {
    if ($JavaHome) {
        $taskJavaName = if ([System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT) { 'java.exe' } else { 'java' }
        if (-not (Test-Path -LiteralPath (Join-Path $JavaHome "bin/$taskJavaName"))) { throw 'JavaHome must contain JDK 17+.' }
        $env:JAVA_HOME = $JavaHome
    }
    New-Item -ItemType Directory -Path $taskArtifacts -Force | Out-Null
    if ($Pack.Count -eq 0) {
        $taskDictionary = Join-Path $taskArtifacts "ecdict-$Version.lrpack"
        & $taskNode (Join-Path $PSScriptRoot 'publish_packs.mjs') dictionary --version $Version --out $taskDictionary
        if ($LASTEXITCODE -ne 0) { throw 'Dictionary package creation failed.' }
        $Pack = @($taskDictionary)
    }
    $taskArguments = @((Join-Path $PSScriptRoot 'publish_packs.mjs'), 'publish', '--root', $taskSite)
    if ($BaseUrl) { $taskArguments += @('--base-url', $BaseUrl) }
    foreach ($taskPack in $Pack) { $taskArguments += @('--pack', (Resolve-Path -LiteralPath $taskPack).Path) }
    & $taskNode @taskArguments
    if ($LASTEXITCODE -ne 0) { throw 'Verified catalogue publication failed.' }
    $taskArguments = @((Join-Path $taskRepo 'deploy/packs/package.mjs'), '--site', $taskSite, '--out', $taskArchive)
    if ($BaseUrl) { $taskArguments += @('--base-url', $BaseUrl) }
    $taskPackageText = & $taskNode @taskArguments
    if ($LASTEXITCODE -ne 0) { throw 'Deployment archive creation failed.' }
    $taskPackage = $taskPackageText | ConvertFrom-Json
    Write-Output "Archive: $($taskPackage.archive)"
    Write-Output "SHA256: $($taskPackage.sha256)"
    if (-not $Deploy) { Write-Output 'Package only; no remote connection was made.'; return }
    if ((Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskPackage.sha256) { throw 'Archive changed after packaging.' }
    $taskRemote = (& ssh -o ConnectTimeout=10 $Server 'mktemp -d /tmp/linguareader-packs.XXXXXXXX').Trim()
    if ($LASTEXITCODE -ne 0 -or $taskRemote -notmatch '^/tmp/linguareader-packs\.[A-Za-z0-9]+$') { throw 'Could not create a private remote stage; no activation was attempted.' }
    & scp -o ConnectTimeout=10 $taskArchive "${Server}:$taskRemote/upload.tar.gz"
    if ($LASTEXITCODE -ne 0) { throw "Upload failed; remote stage retained at $taskRemote" }
    $taskEndpoint = [uri]$taskPackage.baseUrl
    $taskHost = $taskEndpoint.DnsSafeHost
    if ($taskHost -notmatch '^[A-Za-z0-9][A-Za-z0-9.-]*$') { throw 'Unsupported target hostname.' }
    $taskBootstrap = if ($BootstrapCertbot) { '--bootstrap-certbot' } else { '' }
    # Every interpolated remote token is locally validated, generated or a hexadecimal checksum.
    $taskCommand = "set -eu; cd $taskRemote; printf '%s  %s
' '$($taskPackage.sha256)' upload.tar.gz | sha256sum --check -; mkdir stage; tar -xzf upload.tar.gz -C stage; python3 stage/activate.py verify --stage stage; sudo bash stage/install-ubuntu.sh --host $taskHost --port $($taskEndpoint.Port) --existing-webroot $ExistingWebroot --owner $taskOwner $taskBootstrap; python3 stage/activate.py activate --stage stage --root /srv/linguareader-packs/public --base-url $($taskPackage.baseUrl); python3 stage/activate.py check-http --root /srv/linguareader-packs/public --base-url $($taskPackage.baseUrl) --full"
    & ssh -t -o ConnectTimeout=10 $Server $taskCommand
    if ($LASTEXITCODE -ne 0) { throw "Deployment/check incomplete. Stage retained at $taskRemote; inspect actual state before retrying. Existing services were not intentionally stopped." }
    Write-Output "Remote file/TLS/HTTP checks passed. Stage retained at $taskRemote for audit; external network and app checks remain separate."
} finally {
    $env:JAVA_HOME = $taskOldJava
}
