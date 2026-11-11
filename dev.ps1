param(
    [ValidateSet('build','check','release','install','devices')][string]$Action = 'build',
    [string]$Device,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$studioJava = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
if (!$env:JAVA_HOME -or !(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { $env:JAVA_HOME = $studioJava }
if (!(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { throw 'Install Android Studio or set JAVA_HOME.' }
if (!$env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (!(Test-Path -LiteralPath $env:ANDROID_HOME)) { throw 'Android SDK not found. Set ANDROID_HOME.' }
$env:ANDROID_USER_HOME = Join-Path $PSScriptRoot 'artifacts\android-user'
New-Item -ItemType Directory -Path $env:ANDROID_USER_HOME -Force | Out-Null
$properties = 'sdk.dir=' + $env:ANDROID_HOME.Replace('\','/').Replace(':','\:') + "`n"
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'local.properties'), $properties, [Text.UTF8Encoding]::new($false))
$adbPath = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
Push-Location $PSScriptRoot
try {
    if ($Action -eq 'devices') { & $adbPath devices -l; exit $LASTEXITCODE }
    [string[]]$tasks = switch ($Action) {
        'check' { @(':app:testDebugUnitTest', ':app:lintDebug') }
        'release' { @(':app:testDebugUnitTest', ':app:lintRelease', ':app:bundleRelease', ':app:assembleRelease') }
        default { @(':app:assembleDebug') }
    }
    [string[]]$flags = @('--no-daemon','--console=plain'); if ($Offline) { $flags += '--offline' }
    & '.\gradlew.bat' @flags @tasks
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    if ($Action -eq 'install') {
        $selector = if ($Device) { @('-s', $Device) } else { @('-d') }
        & $adbPath @selector install -r 'app\build\outputs\apk\debug\app-debug.apk'
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        & $adbPath @selector shell am start -n 'cz.ecmpilot/.MainActivity'
        exit $LASTEXITCODE
    }
} finally { Pop-Location }
