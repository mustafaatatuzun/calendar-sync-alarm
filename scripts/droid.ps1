# Dot-source from the workfolder root:  . .\scripts\droid.ps1
[Console]::OutputEncoding = [Text.Encoding]::UTF8   # adb output (Turkish letters, emoji, "·") decodes correctly
$Root = Split-Path $PSScriptRoot -Parent
$Proj = Join-Path $Root 'mustafa-alarm'
$Pkg = 'com.atatuzun.mustafaalarm'
$Phone = $env:PHONE_SERIAL  # e.g. 'ABCD12345EFG' — set to your phone's `adb devices` serial
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot'
$env:ANDROID_HOME = 'D:\Program Files\Android\Sdk'
$adb = 'D:\Program Files\Android\Sdk\platform-tools\adb.exe'
$emulatorExe = 'D:\Program Files\Android\Sdk\emulator\emulator.exe'
if (-not $env:DEVICE) { $env:DEVICE = 'emulator-5560' }

function Gradle {
    $env:ANDROID_SERIAL = $env:DEVICE
    & (Join-Path $Proj 'gradlew.bat') -p $Proj @args
    if ($LASTEXITCODE -ne 0) { throw "gradle failed: $args" }
}
function A { & $adb -s $env:DEVICE @args }

function Start-Emu([switch]$Cold) {
    if ((& $adb devices) -match 'emulator-5560\s+device') { return 'emulator already running' }
    # -gpu swiftshader_indirect: force the software renderer. The android-36.1 google_apis x86_64
    # system image ships a broken Goldfish buffer mapper (mapper.ranchu.so) that keeps hitting
    # 'Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma' from SurfaceFlinger's
    # RegionSamplingThread (SIGABRT every ~10 s). Software renderer bypasses the mapper path.
    $emuArgs = @('-avd', 'mustafa_alarm_36', '-port', '5560', '-no-boot-anim', '-gpu', 'swiftshader_indirect')
    if ($Cold) { $emuArgs += '-no-snapshot-load' }   # a real boot (BOOT_COMPLETED), not a snapshot restore
    Start-Process -FilePath $emulatorExe -ArgumentList $emuArgs -WindowStyle Minimized
    & $adb -s emulator-5560 wait-for-device
    Wait-Boot 'emulator-5560'
}
function Wait-Boot([string]$serial = $env:DEVICE) {
    for ($i = 0; $i -lt 120; $i++) {
        if ((& $adb -s $serial shell getprop sys.boot_completed 2>$null) -match '1') { Start-Sleep 5; return "$serial booted" }
        Start-Sleep 2
    }
    throw "$serial did not finish booting in 4 minutes"
}

function New-Evidence([string]$name) { $d = Join-Path $Root "logs\$name"; New-Item -ItemType Directory -Force $d | Out-Null; $d }
function Shot([string]$name) {
    $dir = New-Evidence 'shots'
    A shell screencap -p /sdcard/ma_shot.png | Out-Null
    A pull /sdcard/ma_shot.png (Join-Path $dir "$name.png") | Out-Null
    A shell rm /sdcard/ma_shot.png | Out-Null
    Join-Path $dir "$name.png"
}
function Shot-To([string]$dir, [string]$name) { Copy-Item (Shot $name) (Join-Path $dir "$name.png") -Force }

function UiXml {
    A shell uiautomator dump /sdcard/ma_ui.xml | Out-Null
    $local = Join-Path (New-Evidence 'ui') 'last-ui.xml'
    A pull /sdcard/ma_ui.xml $local | Out-Null
    [xml](Get-Content $local -Raw -Encoding UTF8)
}
function Find-Node([string]$text) { (UiXml).SelectNodes('//node') | Where-Object { $_.text -eq $text -or $_.'content-desc' -eq $text } | Select-Object -First 1 }
function Find-Id([string]$id) { (UiXml).SelectNodes('//node') | Where-Object { $_.'resource-id' -eq $id } | Select-Object -First 1 }
function Tap-Node($n) {
    $b = [regex]::Matches($n.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    A shell input tap ([int](($b[0] + $b[2]) / 2)) ([int](($b[1] + $b[3]) / 2)) | Out-Null
    Start-Sleep -Milliseconds 800
}
function Tap-Text([string]$text) { $n = Find-Node $text; if (-not $n) { throw "not on screen: $text" }; Tap-Node $n }
function Tap-Id([string]$id) { $n = Find-Id $id; if (-not $n) { throw "no node with id: $id" }; Tap-Node $n }
function Assert-Text([string]$text) { if (-not (Find-Node $text)) { throw "expected on screen: $text" }; "ok: '$text' on screen" }
function Assert-TextLike([string]$pattern) {
    $hit = (UiXml).SelectNodes('//node') | Where-Object { $_.text -match $pattern } | Select-Object -First 1
    if (-not $hit) { throw "expected text matching /$pattern/ on screen" }; "ok: '$($hit.text)'"
}

function AppLog {
    $lines = @(A shell run-as $Pkg cat "/data/user_de/0/$Pkg/files/event-log.txt" 2>$null)
    if ($lines.Count -gt 0 -and $lines[0] -match '^\d{4}-\d\d-\d\d ') { return $lines }
    # Before the first unlock after a reboot run-as may fail: same messages from logcat.
    @(A logcat -d -v time -s 'MustafaAlarm:I')
}
$global:AppLogMark = 0
function Mark-AppLog { $global:AppLogMark = @(AppLog).Count }   # later waits only look at lines written after this
function Wait-AppLog([string]$pattern, [int]$seconds = 120) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $lines = @(AppLog)
        $start = if ($lines.Count -lt $global:AppLogMark) { 0 } else { $global:AppLogMark }   # rotated or logcat fallback
        $new = if ($lines.Count -gt $start) { $lines[$start..($lines.Count - 1)] -join "`n" } else { '' }
        if ($new -match $pattern) { return $Matches[0] }
        Start-Sleep 3
    }
    throw "timed out after $seconds s waiting for /$pattern/ in the app log"
}
function Last-CreatedId {
    $m = [regex]::Matches((AppLog | Out-String), 'created eventId=(\d+)')
    if ($m.Count -eq 0) { throw 'no created alarm in the app log' }
    [long]$m[$m.Count - 1].Groups[1].Value
}
function Debug-Cmd([string]$action, [string[]]$extra = @()) {
    A shell am broadcast -n "$Pkg/.debug.DebugCommandReceiver" -a "$Pkg.debug.$action" @extra | Out-Null
    Start-Sleep -Milliseconds 1500
}
function Grant-All {
    foreach ($p in 'READ_CALENDAR', 'WRITE_CALENDAR', 'POST_NOTIFICATIONS') { A shell pm grant $Pkg "android.permission.$p" | Out-Null }
    A shell dumpsys deviceidle whitelist "+$Pkg" | Out-Null
    A shell appops set $Pkg USE_FULL_SCREEN_INTENT allow | Out-Null
}
function Open-App {
    A shell input keyevent KEYCODE_WAKEUP | Out-Null
    A shell wm dismiss-keyguard | Out-Null
    # NEW_TASK|CLEAR_TASK: fresh activity on the start screen, without killing the process (force-stop would cancel alarms)
    A shell am start -n "$Pkg/.ui.MainActivity" -f 0x10008000 | Out-Null
    Start-Sleep 3
}
function Instrument([string]$target) {
    $out = A shell am instrument -w -e class $target "$Pkg.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | Out-String
    $out
    if ($out -notmatch 'OK \(\d+ test') { throw "instrumented tests failed: $target" }
}
function Commit([string]$subject, [string]$Session) {
    $trailer = 'Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>'
    if ($Session) { $trailer += "`nClaude-Session: $Session" }
    git -C $Root commit -m $subject -m $trailer
}
