$ErrorActionPreference = 'Stop'
$repo = 'PandesalPanpan/Nook'
$firebasePath = Join-Path $PSScriptRoot '..\apps\android\app\src\main\assets\nook-firebase.json'
$keyPath = Join-Path $env:USERPROFILE '.nook\signing\nook-release.p12'
$passwordPath = Join-Path $env:USERPROFILE '.nook\signing\store-password.dpapi'

foreach ($path in @($firebasePath, $keyPath, $passwordPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required local release input is missing: $path" }
}

$firebase = Get-Content -Raw -LiteralPath $firebasePath | ConvertFrom-Json
if ($firebase.projectId -ne 'nook-app-e3f48' -or [string]::IsNullOrWhiteSpace($firebase.googleWebClientId)) {
    throw 'The local Android Firebase client config must target nook-app-e3f48 and include googleWebClientId.'
}

$password = ConvertTo-SecureString (Get-Content -Raw -LiteralPath $passwordPath)
$plainPassword = [System.Net.NetworkCredential]::new('', $password).Password
$firebaseBytes = [System.Text.Encoding]::UTF8.GetBytes((Get-Content -Raw -LiteralPath $firebasePath))
$firebaseBase64 = [Convert]::ToBase64String($firebaseBytes)
$keystoreBase64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($keyPath))

function Set-GitHubSecret([string]$name, [string]$value) {
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new('gh')
    $startInfo.ArgumentList.Add('secret')
    $startInfo.ArgumentList.Add('set')
    $startInfo.ArgumentList.Add($name)
    $startInfo.ArgumentList.Add('--repo')
    $startInfo.ArgumentList.Add($repo)
    $startInfo.ArgumentList.Add('--app')
    $startInfo.ArgumentList.Add('actions')
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) { throw "Could not start GitHub CLI for secret $name." }
    $process.StandardInput.Write($value)
    $process.StandardInput.Close()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    $null = $stdoutTask.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) { throw "Could not set Android Actions secret $name. $stderr" }
}

Set-GitHubSecret 'NOOK_FIREBASE_CONFIG_BASE64' $firebaseBase64
Set-GitHubSecret 'NOOK_RELEASE_KEYSTORE_BASE64' $keystoreBase64
Set-GitHubSecret 'NOOK_RELEASE_STORE_PASSWORD' $plainPassword
Set-GitHubSecret 'NOOK_RELEASE_KEY_ALIAS' 'nook-release'
Set-GitHubSecret 'NOOK_RELEASE_KEY_PASSWORD' $plainPassword

$plainPassword = $null
$firebaseBase64 = $null
$keystoreBase64 = $null
$firebaseBytes = $null
Write-Output 'Android release Actions secrets are configured.'
