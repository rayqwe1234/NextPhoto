$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$artifactDir = Join-Path $taskRoot 'artifacts'
$apkSource = Join-Path $taskRoot 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path -LiteralPath $apkSource)) { throw '請先執行 gradlew :app:assembleRelease，並配置本機簽名金鑰。' }
New-Item -ItemType Directory -Force $artifactDir | Out-Null
$apkTarget = Join-Path $artifactDir 'NextPhoto-v0.1.7.apk'
Copy-Item -LiteralPath $apkSource -Destination $apkTarget

# Explicit allowlist: never include credentials, tools, caches or private signing material.
$sourceFiles = @('README.md','THIRD_PARTY.md','LICENSE','.gitignore','.gitattributes','build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat','app/build.gradle.kts','app/proguard-rules.pro') |
    ForEach-Object { Get-Item -LiteralPath (Join-Path $taskRoot $_) }
foreach ($folder in @('app/src','app/schemas','gradle','scripts','docs')) {
    $sourceFiles += Get-ChildItem -LiteralPath (Join-Path $taskRoot $folder) -Recurse -File
}
Add-Type -AssemblyName System.IO.Compression
$zipPath = Join-Path $artifactDir 'NextPhoto-v0.1.7-source.zip'
$stream = [IO.File]::Open($zipPath,[IO.FileMode]::Create)
$archive = [IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in $sourceFiles) {
        $relative = [IO.Path]::GetRelativePath($taskRoot,$file.FullName).Replace('\','/')
        $entry = $archive.CreateEntry($relative,[IO.Compression.CompressionLevel]::Optimal)
        $inputStream = $file.OpenRead()
        $outputStream = $entry.Open()
        try { $inputStream.CopyTo($outputStream) } finally { $inputStream.Dispose(); $outputStream.Dispose() }
    }
} finally { $archive.Dispose(); $stream.Dispose() }
@($apkTarget,$zipPath) | ForEach-Object {
    $hash=Get-FileHash -LiteralPath $_ -Algorithm SHA256
    $hash.Hash.ToLower()+'  '+[IO.Path]::GetFileName($_)
} | Set-Content -LiteralPath (Join-Path $artifactDir 'SHA256SUMS.txt') -Encoding utf8
Get-Item -LiteralPath $apkTarget,$zipPath | Select-Object Name,Length
