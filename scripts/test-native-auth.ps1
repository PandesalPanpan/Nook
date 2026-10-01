$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'Y:\Android\jdk-21'
$env:ANDROID_HOME = 'Y:\Android\Sdk'
$env:GRADLE_USER_HOME = 'Y:\Android\Gradle'
$adbPath = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$configurationPath = Join-Path (Get-Location) 'apps/android/app/src/main/assets/nook-firebase.json'
$originalConfiguration = if (Test-Path -LiteralPath $configurationPath) { [System.IO.File]::ReadAllBytes($configurationPath) } else { $null }
$wifiState = (& $adbPath shell settings get global wifi_on).Trim()
$dataState = (& $adbPath shell settings get global mobile_data).Trim()
$testExitCode = 1
try {
    [System.IO.File]::WriteAllText($configurationPath, '{"apiKey":"demo-nook","applicationId":"1:123456789:android:demo-nook","projectId":"demo-nook","storageBucket":"demo-nook.appspot.com","emulatorHost":"10.0.2.2"}', [System.Text.UTF8Encoding]::new($false))
    & $adbPath shell svc wifi enable
    & $adbPath shell svc data enable
    Push-Location 'apps/android'
    try {
        .\gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=app.nook.NativeAccountFlowTest' '-Pandroid.testInstrumentationRunnerArguments.configuredAuth=true'
        $testExitCode = $LASTEXITCODE
    } finally { Pop-Location }
} finally {
    if ($null -ne $originalConfiguration) { [System.IO.File]::WriteAllBytes($configurationPath, $originalConfiguration) }
    else { Remove-Item -LiteralPath $configurationPath -ErrorAction SilentlyContinue }
    if ($wifiState -eq '1') { & $adbPath shell svc wifi enable } else { & $adbPath shell svc wifi disable }
    if ($dataState -eq '1') { & $adbPath shell svc data enable } else { & $adbPath shell svc data disable }
    Push-Location 'apps/android'
    try {
        .\gradlew.bat assembleDebug
        if ($LASTEXITCODE -ne 0) { $testExitCode = $LASTEXITCODE }
    } finally { Pop-Location }
}
exit $testExitCode
