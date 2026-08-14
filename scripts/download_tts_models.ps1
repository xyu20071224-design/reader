#!/usr/bin/env pwsh
# Downloads the offline TTS voice models for sherpa-onnx.
#
# The .onnx model files are large (>100MB for Chinese voices) and are excluded
# from git. Run this script once before building the app so the models land in
# src/app/src/main/assets/sherpa/.
#
# After downloading, build with:  cd src && ./gradlew assembleDebug

$ErrorActionPreference = "Stop"

$Dest = Join-Path $PSScriptRoot "..\src\app\src\main\assets\sherpa"
New-Item -ItemType Directory -Path $Dest -Force | Out-Null

$Models = @(
    @{
        Name = "vits-zh-hf-fanchen-wnj.tar.bz2"
        Url  = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-hf-fanchen-wnj.tar.bz2"
    },
    @{
        Name = "vits-piper-en_US-ryan-medium.tar.bz2"
        Url  = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ryan-medium.tar.bz2"
    }
)

foreach ($m in $Models) {
    $archive = Join-Path $Dest $m.Name
    if (-not (Test-Path $archive)) {
        Write-Host "Downloading $($m.Name) ..."
        Invoke-WebRequest -Uri $m.Url -OutFile $archive -UseBasicParsing
    } else {
        Write-Host "Already have $($m.Name)"
    }

    Write-Host "Extracting $($m.Name) ..."
    tar -xjf $archive -C $Dest
    Remove-Item $archive -Force

    # Drop the helper scripts that ship inside the archives; they are not
    # needed at runtime.
    Get-ChildItem $Dest -Recurse -Include "*.py", "*.sh" -File -ErrorAction SilentlyContinue |
        Remove-Item -Force -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "Done. Voice models are under $Dest"
