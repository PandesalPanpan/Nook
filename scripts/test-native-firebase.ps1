$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'Y:\Android\jdk-21'
$env:ANDROID_HOME = 'Y:\Android\Sdk'
$env:GRADLE_USER_HOME = 'Y:\Android\Gradle'
$adbPath = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$wifiState = (& $adbPath shell settings get global wifi_on).Trim()
$dataState = (& $adbPath shell settings get global mobile_data).Trim()
try {
    & $adbPath shell svc wifi enable
    & $adbPath shell svc data enable
    Push-Location 'apps/android'
    try {
        .\gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=app.nook.FirebaseTransportTest' '-Pandroid.testInstrumentationRunnerArguments.firebaseEmulators=true'
        $testExitCode = $LASTEXITCODE
    } finally { Pop-Location }
} finally {
    if ($wifiState -eq '1') { & $adbPath shell svc wifi enable } else { & $adbPath shell svc wifi disable }
    if ($dataState -eq '1') { & $adbPath shell svc data enable } else { & $adbPath shell svc data disable }
}
exit $testExitCode
