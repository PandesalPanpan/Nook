#!/usr/bin/env node

import { existsSync } from 'node:fs'
import { readFileSync, readdirSync } from 'node:fs'
import { readFile, writeFile } from 'node:fs/promises'
import { spawnSync } from 'node:child_process'
import { delimiter, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(fileURLToPath(new URL('..', import.meta.url)))
const gradlePath = join(root, 'apps/android/app/build.gradle.kts')
const androidPath = join(root, 'apps/android')
const versionArgument = process.argv[2]

function fail(message) { throw new Error(message) }

function command(executable, args, options = {}) {
  const result = spawnSync(executable, args, {
    cwd: options.cwd ?? root,
    env: options.env ?? process.env,
    encoding: 'utf8',
    stdio: options.capture ? 'pipe' : 'inherit',
    windowsHide: true,
    shell: process.platform === 'win32' && options.batch === true,
  })
  if (result.error) fail(`Could not run ${executable}: ${result.error.message}`)
  if (options.allowFailure) return { status: result.status ?? 1, stdout: `${result.stdout ?? ''}${result.stderr ?? ''}` }
  if (result.status !== 0) {
    const details = options.capture ? `${result.stdout ?? ''}${result.stderr ?? ''}`.trim() : ''
    fail(`${executable} ${args.join(' ')} failed${details ? `:\n${details}` : '.'}`)
  }
  return options.capture ? `${result.stdout ?? ''}${result.stderr ?? ''}`.trim() : ''
}

function compareVersions(left, right) {
  const a = left.split('.').map(Number)
  const b = right.split('.').map(Number)
  for (let index = 0; index < 3; index += 1) {
    if (a[index] !== b[index]) return a[index] - b[index]
  }
  return 0
}

function extractGradleValue(source, key) {
  const value = source.match(new RegExp(`\\b${key}\\s*=\\s*(?:"([^"]+)"|(\\d+))`))
  return value?.[1] ?? value?.[2] ?? null
}

function assertCleanMain() {
  const branch = command('git', ['branch', '--show-current'], { capture: true })
  if (branch !== 'main') fail(`Android releases must be cut from main; current branch is ${branch || '(detached HEAD)'}.`)
  const status = command('git', ['status', '--porcelain=v1'], { capture: true })
  if (status) fail(`The working tree must be clean before a release:\n${status}`)
  const remote = command('git', ['remote', 'get-url', 'origin'], { capture: true })
  if (!/github\.com[:/]PandesalPanpan\/Nook(?:\.git)?$/i.test(remote)) fail(`origin must point to PandesalPanpan/Nook; it currently points to ${remote}.`)
  command('git', ['fetch', 'origin', 'main'])
  const divergence = command('git', ['rev-list', '--left-right', '--count', 'HEAD...origin/main'], { capture: true }).split(/\s+/)
  if (divergence[0] !== '0' || divergence[1] !== '0') fail('Local main must match origin/main before starting an Android release.')
}

function tagExists(tag) {
  const local = command('git', ['show-ref', '--verify', '--quiet', `refs/tags/${tag}`], { allowFailure: true })
  if (local.status === 0) return true
  if (local.status !== 1) fail(`Could not check local tag ${tag}.`)
  const remote = command('git', ['ls-remote', '--exit-code', '--tags', 'origin', `refs/tags/${tag}`], { allowFailure: true })
  if (remote.status === 0) return true
  if (remote.status !== 2 && remote.status !== 1) fail(`Could not check remote tag ${tag}.`)
  return false
}

function loadSigningPassword() {
  if (process.env.NOOK_RELEASE_STORE_PASSWORD) return process.env.NOOK_RELEASE_STORE_PASSWORD
  if (process.platform !== 'win32') fail('Set NOOK_RELEASE_STORE_PASSWORD from your local secret manager before releasing.')
  const profile = process.env.USERPROFILE
  if (!profile) fail('The Nook release signing password is not available.')
  const passwordFile = join(profile, '.nook/signing/store-password.dpapi')
  if (!existsSync(passwordFile)) fail('The DPAPI-protected local Nook release password is missing.')
  const script = '$secure = ConvertTo-SecureString (Get-Content -Raw -LiteralPath $env:NOOK_PASSWORD_FILE); [System.Net.NetworkCredential]::new("", $secure).Password'
  const result = spawnSync('pwsh.exe', ['-NoProfile', '-NonInteractive', '-Command', script], {
    env: { ...process.env, NOOK_PASSWORD_FILE: passwordFile },
    encoding: 'utf8',
    windowsHide: true,
    stdio: 'pipe',
  })
  if (result.error || result.status !== 0 || !result.stdout?.trim()) fail('Could not unlock the DPAPI-protected Nook release password for this Windows account.')
  return result.stdout.trim()
}

function androidSdkRoot() {
  const configured = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT
  if (configured) return configured
  const propertiesPath = join(androidPath, 'local.properties')
  if (!existsSync(propertiesPath)) fail('Set ANDROID_HOME or create apps/android/local.properties before releasing.')
  const source = readFileSync(propertiesPath, 'utf8')
  const sdk = source.match(/^sdk\.dir=(.+)$/m)?.[1]?.replaceAll('\\:', ':')?.replaceAll('\\\\', '\\')
  if (!sdk) fail('Could not resolve the Android SDK from local.properties.')
  return sdk
}

function androidTool(name) {
  const sdk = androidSdkRoot()
  const buildToolsRoot = join(sdk, 'build-tools')
  const versions = readdirSync(buildToolsRoot).sort((a, b) => compareToolVersions(b, a))
  for (const version of versions) {
    const candidate = join(buildToolsRoot, version, process.platform === 'win32' ? `${name}.bat` : name)
    if (existsSync(candidate)) return { executable: candidate, batch: process.platform === 'win32' }
    const exe = join(buildToolsRoot, version, process.platform === 'win32' ? `${name}.exe` : name)
    if (existsSync(exe)) return { executable: exe, batch: process.platform === 'win32' }
  }
  fail(`Android SDK build tool ${name} was not found.`)
}

function compareToolVersions(a, b) {
  const left = a.split('.').map(Number)
  const right = b.split('.').map(Number)
  for (let index = 0; index < Math.max(left.length, right.length); index += 1) {
    const difference = (left[index] ?? 0) - (right[index] ?? 0)
    if (difference) return difference
  }
  return 0
}

function verifyReleaseApk(apkPath, versionName, versionCode) {
  const aapt = androidTool('aapt')
  const badging = command(aapt.executable, ['dump', 'badging', apkPath], { capture: true, batch: aapt.batch })
  const packageInfo = badging.match(/^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'/m)
  if (!packageInfo || packageInfo[1] !== 'app.nook' || packageInfo[2] !== String(versionCode) || packageInfo[3] !== versionName) {
    fail('The release APK package or version does not match Gradle metadata.')
  }
  const signer = androidTool('apksigner')
  const signature = command(signer.executable, ['verify', '--verbose', '--print-certs', apkPath], { capture: true, batch: signer.batch })
  const actual = signature.match(/Signer #1 certificate SHA-256 digest: ([A-Fa-f0-9]+)/)?.[1]?.toUpperCase()
  const expected = readFileSync(join(androidPath, 'release-certificate.sha256'), 'utf8').trim().replaceAll(':', '').toUpperCase()
  if (!actual || actual !== expected) fail('The release APK signature does not match the pinned Nook release certificate.')
}

if (!versionArgument || !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(versionArgument)) {
  fail('Usage: npm run release:android -- <major.minor.patch>, for example 0.3.0')
}

assertCleanMain()
const tag = `v${versionArgument}`
if (tagExists(tag)) fail(`Release tag ${tag} already exists locally or on GitHub.`)
const notesPath = join(root, `docs/release-notes/${tag}.md`)
if (!existsSync(notesPath) || !(await readFile(notesPath, 'utf8')).trim()) fail(`Add and commit release notes at docs/release-notes/${tag}.md before releasing.`)

const originalGradle = await readFile(gradlePath, 'utf8')
const currentName = extractGradleValue(originalGradle, 'versionName')
const currentCode = Number(extractGradleValue(originalGradle, 'versionCode'))
if (!currentName || !Number.isSafeInteger(currentCode) || currentCode < 1) fail('Android version metadata in build.gradle.kts is invalid.')
if (compareVersions(versionArgument, currentName) <= 0) fail(`Version ${versionArgument} must be greater than the current Android version ${currentName}.`)
if (currentCode >= 2_100_000_000) fail('Android versionCode is too close to the platform limit.')
const nextCode = currentCode + 1
const keyPath = join(process.env.USERPROFILE ?? process.env.HOME ?? '', '.nook/signing/nook-release.p12')
if (!existsSync(keyPath)) fail('The permanent Nook release keystore is missing from %USERPROFILE%\.nook\signing.')
const firebasePath = join(androidPath, 'app/src/main/assets/nook-firebase.json')
if (!existsSync(firebasePath)) fail('The local Firebase client configuration is required to build a production release.')
const firebase = JSON.parse(await readFile(firebasePath, 'utf8'))
if (firebase.projectId !== 'nook-app-e3f48' || !firebase.googleWebClientId) fail('The Android Firebase config must target nook-app-e3f48 and include googleWebClientId.')

const password = loadSigningPassword()
const environment = {
  ...process.env,
  NOOK_RELEASE_KEYSTORE_PATH: keyPath,
  NOOK_RELEASE_STORE_PASSWORD: password,
  NOOK_RELEASE_KEY_ALIAS: 'nook-release',
  NOOK_RELEASE_KEY_PASSWORD: password,
}
if (environment.JAVA_HOME) environment.PATH = `${join(environment.JAVA_HOME, 'bin')}${delimiter}${environment.PATH ?? ''}`
const nextGradle = originalGradle
  .replace(/(\bversionCode\s*=\s*)\d+/, `$1${nextCode}`)
  .replace(/(\bversionName\s*=\s*)"[^"]+"/, `$1"${versionArgument}"`)
if (nextGradle === originalGradle || !nextGradle.includes(`versionName = "${versionArgument}"`)) fail('Could not update Android version metadata safely.')

let committed = false
try {
  await writeFile(gradlePath, nextGradle, 'utf8')
  const javaVersion = command('java', ['-version'], { capture: true, env: environment })
  if (!/version "21(?:\.|"|\s)/.test(javaVersion)) fail('Android release builds require JDK 21 on PATH.')
  const gradleWrapper = process.platform === 'win32' ? 'gradlew.bat' : './gradlew'
  command(gradleWrapper, ['--no-daemon', 'test', 'lint', 'assembleDebug', 'assembleRelease'], {
    cwd: androidPath,
    env: environment,
    batch: process.platform === 'win32',
  })
  const apkPath = join(androidPath, 'app/build/outputs/apk/release/app-release.apk')
  if (!existsSync(apkPath)) fail('The signed release APK was not produced.')
  verifyReleaseApk(apkPath, versionArgument, nextCode)
  command('git', ['add', 'apps/android/app/build.gradle.kts'])
  command('git', ['commit', '-m', `chore(android): release ${tag}`])
  command('git', ['tag', '-a', tag, '-m', `Nook ${versionArgument}`])
  committed = true
  command('git', ['push', 'origin', 'main'])
  command('git', ['push', 'origin', `refs/tags/${tag}`])
} catch (error) {
  if (!committed) await writeFile(gradlePath, originalGradle, 'utf8')
  throw error
} finally {
  environment.NOOK_RELEASE_STORE_PASSWORD = ''
  environment.NOOK_RELEASE_KEY_PASSWORD = ''
}

console.log(`Pushed ${tag}. GitHub Actions will build and publish the signed APK after the tag workflow passes.`)
