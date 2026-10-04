# Starts a game on the device (AYN Thor) with the WebSocket debugger on port 45678, forwarded to
# this PC. Optionally installs the freshly built duoOptimized APK first (that restarts the app).
#
#   powershell -ExecutionPolicy Bypass -File thor.ps1 -Iso GT.iso [-Install]
#
# The ISO has to be in the app's own folder on the SD card (an adb-launched file:// path only works
# there): adb shell mv /storage/<sd>/ROMS/psp/<game>.iso $AppDir/<name>.iso, and move it back after.
param(
	[Parameter(Mandatory = $true)][string]$Iso,
	[switch]$Install,
	[string]$AppDir = "/storage/3233-6631/Android/data/org.ppsspp.ppssppduo/files"
)
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = "org.ppsspp.ppssppduo"
$act = "$pkg/org.ppsspp.ppsspp.PpssppActivity"
if ($Install) {
	& $adb install -r "$PSScriptRoot\..\..\android\build\outputs\apk\duo\optimized\android-duo-optimized.apk" | Out-Null
}
# The screen turns off after a minute in menus; keep it on while charging over USB (undo with
# "svc power stayon false").
& $adb shell "svc power stayon usb; input keyevent KEYCODE_WAKEUP; am force-stop $pkg" | Out-Null
# Starting with --debugger and no file shows a "file doesn't exist" dialog; OK it, then send the game
# as a VIEW intent (a path in the args gets mangled).
& $adb shell "am start -n $act --es org.ppsspp.ppsspp.Args '--debugger=45678'" | Out-Null
Start-Sleep 7
& $adb shell "input -d 0 tap 1680 195" | Out-Null
Start-Sleep 2
& $adb shell "am start -a android.intent.action.VIEW -d 'file://$AppDir/$Iso' -n $act" | Out-Null
Start-Sleep 20
& $adb forward tcp:45678 tcp:45678 | Out-Null
Write-Output "Game started; debugger on ws://127.0.0.1:45678/debugger"
