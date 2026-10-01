npx vitest run --config vitest.emulator.config.ts
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& ./scripts/test-web-auth.ps1
exit $LASTEXITCODE
