# End-to-end verification for a running Cobble Recode payload.
#
#   powershell -ExecutionPolicy Bypass -File tools/smoke.ps1
#
# Expects `gradlew runServer` and `gradlew runClient -Ppayload=... -Pserver=...`
# to already be up. Drives the client entirely through the payload's control
# channel, asserts on what it reports back, and leaves screenshots in
# build/shots.
param(
    [string]$Dist = "dist",
    [string]$Shots = "build/shots",
    [int]$ReadyTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$dist = (Resolve-Path -LiteralPath $Dist).Path
# The payload lives in %APPDATA%\cobble now; -Dist only locates the repo.
$data = Join-Path $env:APPDATA "cobble"
$request = Join-Path $data "cobble.control"
$reply = Join-Path $data "cobble.control.out"
if (-not (Test-Path $Shots)) { New-Item -ItemType Directory -Force -Path $Shots | Out-Null }
$shots = (Resolve-Path -LiteralPath $Shots).Path

$script:passed = 0
$script:failed = 0

# The payload only services the control channel from its frame hook, so a
# minimised game window stalls it entirely. The timeout is generous enough to
# ride out a brief stall and specific enough to say what went unanswered.
function Send-Control {
    param([string[]]$Lines, [int]$TimeoutSeconds = 45)
    Remove-Item -LiteralPath $reply -ErrorAction SilentlyContinue
    # Write beside the request and rename over it. WriteAllText truncates first,
    # so the payload - which polls this file every frame - can read it while it
    # is still empty, delete it, and answer nothing at all.
    [IO.File]::WriteAllText("$request.tmp", ($Lines -join "`n") + "`n")
    Move-Item -LiteralPath "$request.tmp" -Destination $request -Force
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline -and -not (Test-Path -LiteralPath $reply)) {
        Start-Sleep -Milliseconds 150
    }
    if (-not (Test-Path -LiteralPath $reply)) { throw "payload did not answer: $($Lines -join '; ')" }
    Start-Sleep -Milliseconds 150
    return (Get-Content -LiteralPath $reply)
}

function Get-State {
    $line = (Send-Control @("state")) | Select-Object -First 1
    $state = @{}
    foreach ($token in ($line -replace '^ok\s+', '') -split '\s+') {
        if ($token -match '^([a-z]+)=(.*)$') { $state[$Matches[1]] = $Matches[2] }
    }
    return $state
}

function Save-Shot {
    param([string]$Name, [string[]]$Setup = @())
    $bmp = Join-Path $data "$Name.bmp"
    Remove-Item -LiteralPath $bmp -ErrorAction SilentlyContinue
    Send-Control ($Setup + @("shot $Name.bmp")) | Out-Null
    $deadline = (Get-Date).AddSeconds(25)
    while ((Get-Date) -lt $deadline -and -not (Test-Path -LiteralPath $bmp)) {
        Start-Sleep -Milliseconds 200
    }
    if (-not (Test-Path -LiteralPath $bmp)) { throw "no frame captured for $Name" }
    Start-Sleep -Milliseconds 400
    Add-Type -AssemblyName System.Drawing
    $image = [System.Drawing.Image]::FromFile($bmp)
    $image.Save((Join-Path $shots "$Name.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $image.Dispose()
    Remove-Item -LiteralPath $bmp -ErrorAction SilentlyContinue
}

function Check {
    param([string]$Name, [bool]$Ok, [string]$Detail = "")
    if ($Ok) {
        $script:passed++
        Write-Host ("  PASS  {0}{1}" -f $Name, $(if ($Detail) { "  ($Detail)" } else { "" }))
    } else {
        $script:failed++
        Write-Host ("  FAIL  {0}{1}" -f $Name, $(if ($Detail) { "  ($Detail)" } else { "" }))
    }
}

# --------------------------------------------------------------- readiness ---
# Minecraft stops rendering entirely while its window is minimised, which stops
# glfwSwapBuffers, which stops the payload's frame hook and therefore the
# control channel. Restore the window before driving anything.
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class WindowState {
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
}
"@
foreach ($process in Get-Process java -ErrorAction SilentlyContinue) {
    if ($process.MainWindowTitle -like "*Minecraft*" -and $process.MainWindowHandle -ne 0) {
        if ([WindowState]::IsIconic($process.MainWindowHandle)) {
            [WindowState]::ShowWindow($process.MainWindowHandle, 9) | Out-Null
            Write-Host "restored the minimised client window"
            Start-Sleep -Seconds 2
        }
    }
}

Write-Host "waiting for the client to reach a world"
$deadline = (Get-Date).AddSeconds($ReadyTimeoutSeconds)
$state = $null
while ((Get-Date) -lt $deadline) {
    try {
        $state = Get-State
        if ($state.world -eq "1" -and $state.screen -eq "0") { break }
        if ($state.screen -eq "1") { Send-Control @("closescreen") | Out-Null }
    } catch { }
    Start-Sleep -Seconds 2
}
if ($null -eq $state -or $state.world -ne "1") { throw "client never reached a world" }
# A client left on a death screen reports world=1 and then never ticks again, so
# every timing and combat check below fails for a reason that is not the client.
Send-Control @("respawn", "cmd difficulty peaceful") | Out-Null
Start-Sleep -Seconds 2
Write-Host "ready: $($state.fb) framebuffer, $($state.entities) entities`n"

# ------------------------------------------------------------------ startup ---
Write-Host "startup"
$log = Get-Content -LiteralPath (Join-Path $data "cobble.log") -Raw
Check "mappings fully resolved" ($log -match "resolved (\d+)/\1 classes, (\d+)/\2 methods, (\d+)/\3 fields")
Check "no unresolved symbols" (-not ($log -match "unresolved"))
Check "frame hook installed" ($log -match "install frame hook ok")
Check "shaders linked" (($log | Select-String -Pattern "linked as program" -AllMatches).Matches.Count -ge 4)
Check "no errors in the log" (-not ($log -match "ERROR")) $(if ($log -match "ERROR") { "see cobble.log" })

# ------------------------------------------------------------- clean slate ---
Send-Control @(
    "module TriggerBot off", "module Clutch off", "module Displace off",
    "module FastPlace off", "module FastMine off", "module AutoTool off",
    "module BridgeAssist off", "module JumpReset off", "module CuteVisuals off",
    "module Trajectories off", "module TargetHud off", "module ESP off", "gui off",
    "cmd difficulty peaceful", "cmd gamemode creative",
    "cmd kill @e[type=!player]"
) | Out-Null
Start-Sleep -Seconds 2

# ---------------------------------------------------------------- overlays ---
Write-Host "`nrendering"
Save-Shot "hud"
Check "HUD frame captured" (Test-Path (Join-Path $shots "hud.png"))

Save-Shot "gui" @("gui on", "screen Player", "expand Clutch on")
Check "menu frame captured" (Test-Path (Join-Path $shots "gui.png"))
Send-Control @("gui off") | Out-Null

$idle = Get-State
Check "overlay under 1.5 ms" ([double]($idle.overlay -replace 'us$', '') -lt 1500) "$($idle.overlay)"

# ------------------------------------------------------------- tick timing ---
Write-Host "`ntiming"
$before = Get-State
Start-Sleep -Seconds 5
$after = Get-State
$ticks = [int]$after.ticks - [int]$before.ticks
$ages = [int]$after.age - [int]$before.age
# The absolute rate depends on whether Windows is throttling an unfocused
# window, which is not the client's business. What has to hold is that the
# client's own tick count matches the game's, including when the game runs
# several ticks inside one frame to catch up.
Check "tick count matches the game's own" ($ticks -eq $ages -and $ticks -gt 20) `
    "$ticks ticks in 5 s, game counted $ages"

# --------------------------------------------------------------- shader esp ---
Write-Host "`nESP"
# Armour stands, not zombies, and the difficulty stays where the clean slate
# left it. A suite that raises the difficulty and spawns hostiles beside a client
# nobody is sitting at will eventually come back to a corpse on a death screen -
# and every check after that point fails for a reason that has nothing to do
# with what it was testing. See [[build-and-test]].
Send-Control @(
    "cmd summon minecraft:armor_stand ~2 ~ ~4 {NoGravity:1b}",
    "cmd summon minecraft:armor_stand ~-2 ~ ~5 {NoGravity:1b}",
    "module ESP on", "set ESP Mobs true", "set ESP Distance true"
) | Out-Null
Start-Sleep -Seconds 2
Save-Shot "esp"
$espState = Get-State
Check "ESP frame captured" (Test-Path (Join-Path $shots "esp.png"))
Check "ESP costs little" ([double]($espState.overlay -replace 'us$', '') -lt 2500) "$($espState.overlay)"


# ------------------------------------------------------------ gpu expansion ---
# The quad expansion runs as a compute kernel where the driver allows it, and on
# the CPU where it does not. Both paths fill the same buffer from the same
# records, so the assertion is that the frame is still drawn and still cheap
# either way - not that a particular path was taken, which is a property of the
# machine rather than of the client. See
# docs/compute-shaders-in-a-borrowed-context.md.
Write-Host "`nGPU expansion"
$log = Get-Content -LiteralPath (Join-Path $data "cobble.log") -Raw
$hasKernel = $log -match "quad expansion runs on the GPU"
$loaded = Get-State
Check "capabilities probed" ($log -match "\[gl\] caps \d+\.\d+") `
    $(if ($log -match "(\[gl\] caps [^\r\n]+)") { $Matches[1] } else { "no caps line" })

if ($hasKernel) {
    Send-Control @("gpu on") | Out-Null
    Start-Sleep -Seconds 2
    $onState = Get-State
    Save-Shot "expand-gpu"
    Send-Control @("gpu off") | Out-Null
    Start-Sleep -Seconds 2
    $offState = Get-State
    Save-Shot "expand-cpu"
    Send-Control @("gpu on") | Out-Null

    Check "kernel and fallback both draw" `
        ((Test-Path (Join-Path $shots "expand-gpu.png")) -and
         (Test-Path (Join-Path $shots "expand-cpu.png")))
    Check "the batch is not empty" ([int]$onState.batch -gt 20) "$($onState.batch) quads"
    # Not asserted as a strict inequality: the two are within noise of each other
    # on a light frame, and a suite that fails when nothing is on screen is a
    # suite people stop running.
    Write-Host ("  note  GPU {0} vs CPU {1} at {2} quads" -f `
        $onState.overlay, $offState.overlay, $onState.batch)
} else {
    Check "no kernel, and the client says so" ($log -match "quad expansion runs on the CPU")
}

# ------------------------------------------------------------------ trigger ---
# The one module that puts a packet on the wire from a held button, and the one
# that ever flagged against a real GrimAC server. Both halves are asserted: the
# hit lands, and it lands in the vanilla order.
Write-Host "`nTriggerBot"
Send-Control @(
    "module ESP off",
    "cmd kill @e[type=!player]",
    "cmd tp @s 0.5 -60 0.5 0 17",
    "cmd summon minecraft:armor_stand 0.5 -60 2.5 {NoGravity:1b}"
) | Out-Null
Start-Sleep -Seconds 2

$logBefore = @(Get-Content -LiteralPath (Join-Path $data "cobble.log")).Count
$grimLog = Join-Path (Split-Path -Parent $dist) "testenv/grim-server/logs/latest.log"
$grimBefore = if (Test-Path $grimLog) {
    @(Get-Content -LiteralPath $grimLog | Select-String -Pattern "Grim . " -SimpleMatch:$false).Count
} else { -1 }

Send-Control @(
    "module TriggerBot on",
    "set TriggerBot Targets All",
    "set TriggerBot Mode Any",
    "set TriggerBot Range 5.0",
    "hold attack on"
) | Out-Null
Start-Sleep -Seconds 6
Send-Control @("hold attack off", "module TriggerBot off") | Out-Null

$added = @(Get-Content -LiteralPath (Join-Path $data "cobble.log")) | Select-Object -Skip $logBefore
Check "a target was acquired and hit" `
    ((($added -join "`n") -match "\[trigger\] first hit on entity")) `
    $(($added | Where-Object { $_ -match "\[trigger\]" } | Select-Object -Last 1) -replace "^.*\[trigger\] ", "")

if ($grimBefore -ge 0) {
    $grimAfter = @(Get-Content -LiteralPath $grimLog | Select-String -Pattern "Grim . " -SimpleMatch:$false).Count
    # The hit is queued onto the vanilla binding, so the game sends it from
    # handleInputEvents in front of the movement packet. Sending it from the
    # frame hook instead put it behind one, which is what Grim's Post check
    # counts, and at thirty frames a second it counted dozens.
    Check "GrimAC raised nothing" ($grimAfter -eq $grimBefore) `
        "$grimBefore -> $grimAfter alerts"
} else {
    Write-Host "  skip  GrimAC log not found at $grimLog"
}

# ------------------------------------------------------------------- clutch ---
# The module that proved the Post rule. It aims correctly, confirms the rotation
# the server already holds lands on the face, and obeys the interaction guard -
# and still flagged on essentially every placement while it sent the placement
# itself, because none of that changes where in the tick the packet leaves. Both
# halves are asserted: the fall is caught, and GrimAC stays quiet while it is.
Write-Host "`nClutch"
Send-Control @(
    "module TriggerBot off",
    "cmd gamemode creative",
    "cmd fill 19 -61 19 21 -41 21 minecraft:stone hollow",
    "cmd fill 20 -60 20 20 -42 20 minecraft:air",
    "cmd fill 20 -50 20 20 -44 20 minecraft:air",
    "cmd gamemode survival",
    "cmd item replace entity @s weapon.mainhand with minecraft:obsidian 64",
    "set Clutch Bind 1",
    "module Clutch on",
    "hold use on"
) | Out-Null

$grimBefore = if (Test-Path $grimLog) {
    @(Get-Content -LiteralPath $grimLog | Select-String -Pattern "Grim . " -SimpleMatch:$false).Count
} else { -1 }

# Dropped only after the bind is already down: the fall lasts about two seconds
# and a second control batch would not land inside it.
Send-Control @("cmd tp @s 20.5 -43 20.5 0 30") | Out-Null
Start-Sleep -Seconds 5
Send-Control @("hold use off") | Out-Null
$clutchState = Get-State
Send-Control @("module Clutch off") | Out-Null

# Landing anywhere above the shaft floor means it caught itself on something it
# put there: the column below -42 was emptied, so there is nothing else to land
# on until -60.
$landedY = [double](($clutchState.pos -split ",")[1])
Check "the fall was caught on a placed block" ($landedY -gt -58.0) "landed at y=$landedY"

if ($grimBefore -ge 0) {
    $grimAfter = @(Get-Content -LiteralPath $grimLog | Select-String -Pattern "Grim . " -SimpleMatch:$false).Count
    Check "GrimAC raised nothing for the placements" ($grimAfter -eq $grimBefore) `
        "$grimBefore -> $grimAfter alerts"
}

# ------------------------------------------------------------------- player ---
# The two cooldown modules, which are the ones that break silently: both work by
# lowering a counter the game decrements, and a version that writes it instead
# of lowering it stops the thing it was meant to speed up.
Write-Host "`nPlayer modules"
Send-Control @(
    "cmd gamemode survival",
    "cmd item replace entity @s weapon.mainhand with minecraft:obsidian 64",
    "module FastPlace on", "set FastPlace Tick delay 0",
    "module FastMine on", "set FastMine Break delay 0"
) | Out-Null
Start-Sleep -Seconds 2
$playerState = Get-State
Check "player modules run without faulting" ($playerState.world -eq "1") "world still up"
Save-Shot "player"
Send-Control @("module FastPlace off", "module FastMine off") | Out-Null

$log = Get-Content -LiteralPath (Join-Path $data "cobble.log") -Raw
Check "still no errors in the log" (-not ($log -match "ERROR")) `
    $(if ($log -match "(ERROR[^\r\n]*)") { $Matches[1] } else { "" })

# ------------------------------------------------------------------ tidy up ---
# The suite writes settings, and the payload saves its config on unload. Put
# back what it changed so a run does not silently redefine the shipped defaults.
Send-Control @(
    "set TriggerBot Targets Players",
    "set TriggerBot Mode Smart",
    "set TriggerBot Range 2.90",
    "set Clutch Bind 0",
    "set FastPlace Tick delay 1",
    "set FastMine Break delay 250",
    "set ESP Mobs false",
    "set ESP Distance false",
    "cmd difficulty peaceful",
    "cmd kill @e[type=!player]",
    "gui off"
) | Out-Null

# ------------------------------------------------------------------ summary ---
Write-Host "`n$script:passed passed, $script:failed failed"
if ($script:failed -gt 0) { exit 1 }
