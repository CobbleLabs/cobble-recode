# Sends one control batch to a running payload and converts the frame it grabs
# to PNG. Commands are separated by ';' so the whole batch is a single argument.
#
#   powershell -File tools/shoot.ps1 -Script "gui on;module ESP on;shot a.bmp" -Png build/shots/a.png
param(
    [Parameter(Mandatory = $true)][string]$Script,
    [string]$Png = "",
    [string]$Dist = "dist",
    [int]$TimeoutSeconds = 30
)

$dist = (Resolve-Path -LiteralPath $Dist).Path
# The payload lives in %APPDATA%\cobble now; -Dist only says where the DLL
# under test came from (used for nothing but resolving).
$data = Join-Path $env:APPDATA "cobble"
$request = Join-Path $data "cobble.control"
$reply = Join-Path $data "cobble.control.out"

Remove-Item -LiteralPath $reply -ErrorAction SilentlyContinue
$lines = $Script -split ';' | ForEach-Object { $_.Trim() } | Where-Object { $_ }
[IO.File]::WriteAllText($request, ($lines -join "`n"))

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline -and -not (Test-Path -LiteralPath $reply)) {
    Start-Sleep -Milliseconds 200
}
if (-not (Test-Path -LiteralPath $reply)) {
    Write-Error "payload did not answer within $TimeoutSeconds s"
    exit 1
}
Start-Sleep -Milliseconds 200
Get-Content -LiteralPath $reply

$shot = $lines | Where-Object { $_ -like "shot *" } | Select-Object -Last 1
if (-not $shot) { exit 0 }

$bmp = Join-Path $data (($shot -split "\s+")[1])
# The shot is queued for a few frames after the batch, so the reply arrives
# before the bitmap does.
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    if (Test-Path -LiteralPath $bmp) {
        $size = (Get-Item -LiteralPath $bmp).Length
        Start-Sleep -Milliseconds 300
        if ((Get-Item -LiteralPath $bmp).Length -eq $size -and $size -gt 0) { break }
    }
    Start-Sleep -Milliseconds 200
}
if (-not (Test-Path -LiteralPath $bmp)) {
    Write-Error "no bitmap produced"
    exit 1
}

if (-not $Png) {
    $Png = Join-Path "build/shots" ([IO.Path]::GetFileNameWithoutExtension($bmp) + ".png")
}
$directory = Split-Path -Parent $Png
if ($directory -and -not (Test-Path $directory)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}

Add-Type -AssemblyName System.Drawing
$image = [System.Drawing.Image]::FromFile($bmp)
$image.Save((Join-Path (Resolve-Path $directory).Path (Split-Path -Leaf $Png)),
            [System.Drawing.Imaging.ImageFormat]::Png)
$image.Dispose()
Remove-Item -LiteralPath $bmp -ErrorAction SilentlyContinue
Write-Output "png  $Png"
