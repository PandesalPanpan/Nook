$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'Y:/Android/jdk-21'
$env:ANDROID_HOME = 'Y:/Android/Sdk'
$env:GRADLE_USER_HOME = 'Y:/Android/Gradle'
$adbPath = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$configurationPath = Join-Path (Get-Location) 'apps/android/app/src/main/assets/nook-firebase.json'
$originalConfiguration = if (Test-Path -LiteralPath $configurationPath) { [System.IO.File]::ReadAllBytes($configurationPath) } else { $null }
$wifiState = (& $adbPath shell settings get global wifi_on).Trim()
$dataState = (& $adbPath shell settings get global mobile_data).Trim()
$prepared = $false
$testExitCode = 1
function Invoke-ColdPhase([string]$phase) {
    & $adbPath shell am force-stop app.nook
    $result = & $adbPath shell am instrument -w -e class "app.nook.ColdAccountSurfaceTest#$phase" -e configuredCold true app.nook.test/androidx.test.runner.AndroidJUnitRunner
    $result | Write-Output
    if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'OK \(1 test\)') { throw "Cold account surface phase failed: $phase" }
}
try {
    [System.IO.File]::WriteAllText($configurationPath, '{"apiKey":"demo-nook","applicationId":"1:123456789:android:demo-nook","projectId":"demo-nook","storageBucket":"demo-nook.appspot.com","emulatorHost":"10.0.2.2"}', [System.Text.UTF8Encoding]::new($false))
    & ./apps/android/gradlew.bat -p apps/android assembleDebug assembleDebugAndroidTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Configured APK build failed' }
    & $adbPath install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw 'Configured APK install failed' }
    & $adbPath install -r apps/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Test APK install failed' }
    & $adbPath shell appwidget grantbind --package app.nook --user 0
    & $adbPath shell pm grant app.nook android.permission.POST_NOTIFICATIONS
    & $adbPath shell svc wifi enable
    & $adbPath shell svc data enable
    $prepared = $true
    Invoke-ColdPhase 'cleanup'
    Invoke-ColdPhase 'disconnected'
    Invoke-ColdPhase 'prepare'
    & $adbPath shell svc wifi disable
    & $adbPath shell svc data disable
    foreach ($phase in @('widget','reminder','shortcut')) { Invoke-ColdPhase $phase }
    $testExitCode = 0
} catch {
    Write-Output $_
} finally {
    if ($prepared) {
        try { Invoke-ColdPhase 'cleanup'; Invoke-ColdPhase 'disconnected' } catch { Write-Output $_; $testExitCode = 1 }
    }
    if ($null -ne $originalConfiguration) { [System.IO.File]::WriteAllBytes($configurationPath, $originalConfiguration) }
    else { Remove-Item -LiteralPath $configurationPath -ErrorAction SilentlyContinue }
    if ($wifiState -eq '1') { & $adbPath shell svc wifi enable } else { & $adbPath shell svc wifi disable }
    if ($dataState -eq '1') { & $adbPath shell svc data enable } else { & $adbPath shell svc data disable }
    & ./apps/android/gradlew.bat -p apps/android assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { $testExitCode = $LASTEXITCODE }
    else {
        & $adbPath install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
        if ($LASTEXITCODE -ne 0) { $testExitCode = $LASTEXITCODE }
    }
}
exit $testExitCode
