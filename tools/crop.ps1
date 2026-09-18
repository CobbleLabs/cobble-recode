# Crops a region out of a capture and scales it up with nearest-neighbour, so
# per-pixel rendering issues are actually visible.
#
#   powershell -File tools/crop.ps1 -In build/shots/gui.png -Out build/shots/zoom.png -X 0 -Y 0 -W 320 -H 180 -Zoom 4
param(
    [Parameter(Mandatory = $true)][string]$In,
    [Parameter(Mandatory = $true)][string]$Out,
    [int]$X = 0,
    [int]$Y = 0,
    [int]$W = 320,
    [int]$H = 180,
    [int]$Zoom = 3
)

Add-Type -AssemblyName System.Drawing
$source = [System.Drawing.Image]::FromFile((Resolve-Path -LiteralPath $In).Path)

$W = [Math]::Min($W, $source.Width - $X)
$H = [Math]::Min($H, $source.Height - $Y)

$target = New-Object System.Drawing.Bitmap ($W * $Zoom), ($H * $Zoom)
$graphics = [System.Drawing.Graphics]::FromImage($target)
$graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$graphics.DrawImage($source,
    (New-Object System.Drawing.Rectangle 0, 0, ($W * $Zoom), ($H * $Zoom)),
    (New-Object System.Drawing.Rectangle $X, $Y, $W, $H),
    [System.Drawing.GraphicsUnit]::Pixel)
$graphics.Dispose()

$directory = Split-Path -Parent $Out
if ($directory -and -not (Test-Path $directory)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}
$target.Save((Join-Path (Resolve-Path $directory).Path (Split-Path -Leaf $Out)),
             [System.Drawing.Imaging.ImageFormat]::Png)
$target.Dispose()
$source.Dispose()
Write-Output "cropped ${W}x${H} at ${X},${Y} scaled ${Zoom}x -> $Out"
