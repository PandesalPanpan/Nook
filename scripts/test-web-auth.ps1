$ErrorActionPreference = 'Stop'
$env:VITE_FIREBASE_API_KEY = 'demo-nook'
$env:VITE_FIREBASE_PROJECT_ID = 'demo-nook'
$env:VITE_FIREBASE_APP_ID = 'demo-nook'
$env:VITE_FIREBASE_AUTH_DOMAIN = 'demo-nook.firebaseapp.com'
$env:VITE_FIREBASE_STORAGE_BUCKET = 'demo-nook.appspot.com'
$env:VITE_FIREBASE_EMULATOR_HOST = '127.0.0.1'
$env:NOOK_AUTH_E2E = '1'
npm run build
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
npx playwright test accounts.spec.ts
$flowExitCode = $LASTEXITCODE
# Restore the ordinary build so emulator credentials never become the default artifact.
$env:VITE_FIREBASE_API_KEY = $null
$env:VITE_FIREBASE_PROJECT_ID = $null
$env:VITE_FIREBASE_APP_ID = $null
$env:VITE_FIREBASE_AUTH_DOMAIN = $null
$env:VITE_FIREBASE_STORAGE_BUCKET = $null
$env:VITE_FIREBASE_EMULATOR_HOST = $null
$env:NOOK_AUTH_E2E = $null
npm run build
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
exit $flowExitCode
