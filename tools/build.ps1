# Dev-only build for Cobble Recode.
#
#   powershell -ExecutionPolicy Bypass -File tools/build.ps1
#   powershell -ExecutionPolicy Bypass -File tools/build.ps1 -Config Release
#   powershell -ExecutionPolicy Bypass -File tools/build.ps1 -JavaHome "C:\Path\To\JDK"
#   powershell -ExecutionPolicy Bypass -File tools/build.ps1 -SkipGenerators
#   powershell -ExecutionPolicy Bypass -File tools/build.ps1 -NoAutoInstall
#
# CMake is autodetected (PATH plus the usual install locations plus any
# previous portable install under build/tools). When nothing is found the
# script installs it automatically: winget first, then Chocolatey, then a
# user-local portable ZIP from Kitware. Pass -NoAutoInstall to disable that
# and fail with an error instead.
#
# Steps:
#   1. node tools/genmappings.mjs
#   2. node tools/packassets.mjs
#   3. cmake -S . -B <BuildDir> -A x64 -DCOLD_JAVA_HOME=<jdk>
#   4. cmake --build <BuildDir> --config <Config>
param(
    [ValidateSet("Release", "Debug", "RelWithDebInfo", "MinSizeRel")]
    [string]$Config = "Release",
    [string]$BuildDir = "build/native",
    [string]$JavaHome = "",
    [string]$CmakeVersion = "3.30.5",
    [switch]$SkipGenerators,
    [switch]$NoAutoInstall
)

$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "================================================================" -ForegroundColor Yellow
Write-Host "  DEV BUILD ONLY - NO PROTECTION" -ForegroundColor Yellow
Write-Host "================================================================" -ForegroundColor Yellow
Write-Host "  This is a development build. It has NO protection:"
Write-Host "    - No obfuscation"
Write-Host "    - No license wall / auth server"
Write-Host "    - No HWID binding"
Write-Host "    - No debugger or tamper checks"
Write-Host "  Local use only. Do not distribute binaries built with this script."
Write-Host "================================================================" -ForegroundColor Yellow
Write-Host ""

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

function Find-CommandPath {
    param([string]$Name, [string[]]$Fallbacks = @())
    $found = Get-Command $Name -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    foreach ($fallback in $Fallbacks) {
        if (Test-Path -LiteralPath $fallback) { return $fallback }
    }
    return $null
}

function Find-CMake {
    $portable = @()
    foreach ($root in @((Join-Path $RepoRoot "build\tools"), (Join-Path $env:LOCALAPPDATA "CobbleBuild"))) {
        if (Test-Path -LiteralPath $root) {
            $hits = Get-ChildItem -Path $root -Filter "cmake.exe" -Recurse -ErrorAction SilentlyContinue |
                Where-Object { $_.FullName -like "*\bin\cmake.exe" } |
                Select-Object -First 1
            if ($hits) { $portable += $hits.FullName }
        }
    }
    return (Find-CommandPath "cmake" @(
        "C:\Program Files\CMake\bin\cmake.exe",
        "C:\Program Files (x86)\CMake\bin\cmake.exe"
    ) + $portable)
}

function Invoke-Native {
    param([string]$Exe, [string[]]$StepArgs = @())
    & $Exe @StepArgs
    return $LASTEXITCODE
}

function Install-CMakePortable {
    param([string]$Version)
    $zipName = "cmake-$Version-windows-x86_64.zip"
    $url = "https://github.com/Kitware/CMake/releases/download/v$Version/$zipName"
    $destRoot = Join-Path $RepoRoot "build\tools\cmake-$Version"
    $zipPath = Join-Path $env:TEMP $zipName
    Write-Host "Downloading portable CMake $Version ..." -ForegroundColor Cyan
    Write-Host "    $url"
    try {
        if (Test-Path -LiteralPath $destRoot) {
            Remove-Item -LiteralPath $destRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
        New-Item -ItemType Directory -Force -Path $destRoot | Out-Null
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $url -OutFile $zipPath -UseBasicParsing
        Expand-Archive -LiteralPath $zipPath -DestinationPath $destRoot -Force
        $exe = Get-ChildItem -Path $destRoot -Filter "cmake.exe" -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -like "*\bin\cmake.exe" } |
            Select-Object -First 1
        if (-not $exe) {
            Write-Host "Portable CMake download did not contain bin/cmake.exe." -ForegroundColor Yellow
            return $null
        }
        Write-Host "Portable CMake ready: $($exe.FullName)" -ForegroundColor Green
        return $exe.FullName
    } catch {
        Write-Host ("Portable CMake install failed: " + $_.Exception.Message) -ForegroundColor Yellow
        return $null
    } finally {
        if (Test-Path -LiteralPath $zipPath) {
            Remove-Item -LiteralPath $zipPath -Force -ErrorAction SilentlyContinue
        }
    }
}

function Ensure-CMake {
    param([string]$Version, [switch]$NoInstall)
    $exe = Find-CMake
    if ($exe) { return $exe }
    if ($NoInstall) {
        Write-Error "cmake was not found. Install CMake 3.21+ (with PATH entry) or rerun without -NoAutoInstall."
        exit 1
    }
    Write-Host "cmake was not found. Installing automatically ..." -ForegroundColor Yellow

    $winget = Get-Command "winget" -ErrorAction SilentlyContinue
    if ($winget) {
        Write-Host "Trying winget (Kitware.CMake) ..." -ForegroundColor Cyan
        $code = Invoke-Native "winget" @("install", "-e", "--id", "Kitware.CMake", "--silent",
            "--accept-package-agreements", "--accept-source-agreements")
        $env:Path = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" +
            [System.Environment]::GetEnvironmentVariable("Path", "User")
        $exe = Find-CMake
        if ($code -eq 0 -and $exe) {
            Write-Host "CMake installed via winget: $exe" -ForegroundColor Green
            return $exe
        }
        Write-Host "winget install did not yield cmake, trying next method." -ForegroundColor Yellow
    } else {
        Write-Host "winget not available, trying next method." -ForegroundColor DarkYellow
    }

    $choco = Get-Command "choco" -ErrorAction SilentlyContinue
    if ($choco) {
        Write-Host "Trying Chocolatey (cmake) ..." -ForegroundColor Cyan
        $code = Invoke-Native "choco" @("install", "cmake", "-y", "--no-progress")
        $env:Path = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" +
            [System.Environment]::GetEnvironmentVariable("Path", "User")
        $exe = Find-CMake
        if ($code -eq 0 -and $exe) {
            Write-Host "CMake installed via Chocolatey: $exe" -ForegroundColor Green
            return $exe
        }
        Write-Host "Chocolatey install did not yield cmake, trying portable ZIP." -ForegroundColor Yellow
    } else {
        Write-Host "Chocolatey not available, falling back to portable ZIP." -ForegroundColor DarkYellow
    }

    $exe = Install-CMakePortable -Version $Version
    if ($exe) { return $exe }
    Write-Error ("Automatic CMake install failed. Install CMake $Version+ manually " +
        "from https://cmake.org/download/ and rerun.")
    exit 1
}

function Invoke-Step {
    param([string]$Label, [string]$Exe, [string[]]$StepArgs = @())
    Write-Host ""
    Write-Host "==> $Label" -ForegroundColor Cyan
    Write-Host ("    " + $Exe + " " + ($StepArgs -join " "))
    & $Exe @StepArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Error "$Label failed with exit code $LASTEXITCODE"
        exit $LASTEXITCODE
    }
}

# ------------------------------------------------------------ prerequisites ---
$NodeExe = Find-CommandPath "node"
if (-not $NodeExe) {
    Write-Error "node was not found in PATH. Install Node 18+ and retry."
    exit 1
}

$CmakeExe = Ensure-CMake -Version $CmakeVersion -NoInstall:$NoAutoInstall

# ------------------------------------------------------------- JDK location ---
if (-not $JavaHome) { $JavaHome = $env:COLD_JAVA_HOME }
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome) {
    $probe = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
    if (Test-Path -LiteralPath (Join-Path $probe "include\jni.h")) {
        $JavaHome = $probe
    }
}
if (-not $JavaHome) {
    Write-Error "No JDK found. Pass -JavaHome <path> or set JAVA_HOME to a JDK with include/jni.h."
    exit 1
}
$JniHeader = Join-Path $JavaHome "include\jni.h"
if (-not (Test-Path -LiteralPath $JniHeader)) {
    Write-Error "COLD_JAVA_HOME does not contain include/jni.h: $JavaHome"
    exit 1
}

Write-Host "Repo:      $RepoRoot"
Write-Host "Config:    $Config"
Write-Host "BuildDir:  $BuildDir"
Write-Host "JDK:       $JavaHome"
Write-Host "Node:      $NodeExe"
Write-Host "CMake:     $CmakeExe"
& $CmakeExe --version | Select-Object -First 1

# ---------------------------------------------------------------- generators --
if (-not $SkipGenerators) {
    Invoke-Step "[1/4] Generating JNI mapping tables" $NodeExe @("tools/genmappings.mjs")
    Invoke-Step "[2/4] Packing fonts and icons" $NodeExe @("tools/packassets.mjs")
} else {
    Write-Host ""
    Write-Host "==> [1/4] Skipped generators (-SkipGenerators)" -ForegroundColor DarkYellow
    Write-Host "==> [2/4] Skipped generators (-SkipGenerators)" -ForegroundColor DarkYellow
}

# ------------------------------------------------------------------- cmake ----
Invoke-Step "[3/4] Configuring ($Config|x64)" $CmakeExe @(
    "-S", ".",
    "-B", $BuildDir,
    "-A", "x64",
    "-DCOLD_JAVA_HOME=$JavaHome"
)

Invoke-Step "[4/4] Building ($Config)" $CmakeExe @(
    "--build", $BuildDir,
    "--config", $Config
)

# -------------------------------------------------------------------- report --
Write-Host ""
Write-Host "Build OK ($Config|x64)." -ForegroundColor Green
$Dll = Join-Path $RepoRoot "dist\cobble.dll"
$Loader = Join-Path $RepoRoot "dist\cobble-injector.exe"
foreach ($artifact in @($Dll, $Loader)) {
    if (Test-Path -LiteralPath $artifact) {
        $item = Get-Item -LiteralPath $artifact
        Write-Host ("  {0}  ({1:N1} KiB)" -f $item.Name, ($item.Length / 1KB))
    } else {
        Write-Host ("  MISSING: {0}" -f $artifact) -ForegroundColor Yellow
    }
}
Write-Host ""
Write-Host "REMINDER: dev build, no protection. Local use only." -ForegroundColor Yellow
