#!/usr/bin/env node

import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'

const args = new Map()
for (let index = 2; index < process.argv.length; index += 2) {
  const key = process.argv[index]
  const value = process.argv[index + 1]
  if (!key?.startsWith('--') || !value) throw new Error('Arguments must be supplied as --name value pairs.')
  args.set(key.slice(2), value)
}

const output = args.get('output')
const tag = args.get('tag')
const sha256 = args.get('sha256')?.toLowerCase()
const publishedAt = args.get('published-at')
if (!output || !tag || !sha256 || !publishedAt) {
  throw new Error('Usage: node scripts/create-android-update-manifest.mjs --output <path> --tag <vX.Y.Z> --sha256 <sha256> --published-at <RFC3339>')
}
if (!/^v(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)$/.test(tag)) throw new Error('The Android release tag must be v<major>.<minor>.<patch>.')
if (!/^[a-f0-9]{64}$/.test(sha256)) throw new Error('The APK SHA-256 value is invalid.')
if (!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/.test(publishedAt) || Number.isNaN(Date.parse(publishedAt))) {
  throw new Error('The release publication date must be an RFC3339 UTC timestamp.')
}

const gradle = await readFile(new URL('../apps/android/app/build.gradle.kts', import.meta.url), 'utf8')
function readGradleValue(name) {
  const match = gradle.match(new RegExp(`\\b${name}\\s*=\\s*(?:"([^"]+)"|(\\d+))`))
  return match?.[1] ?? match?.[2] ?? null
}

const packageName = readGradleValue('applicationId')
const versionCode = Number(readGradleValue('versionCode'))
const versionName = readGradleValue('versionName')
const minimumSdk = Number(readGradleValue('minSdk'))
if (packageName !== 'app.nook') throw new Error(`Expected package app.nook; Gradle contains ${packageName ?? '(missing)'}.`)
if (!Number.isSafeInteger(versionCode) || versionCode < 1) throw new Error('Gradle must define a positive Android versionCode.')
if (!versionName || tag !== `v${versionName}`) throw new Error(`Release tag ${tag} must match Gradle versionName ${versionName ?? '(missing)'}.`)
if (!Number.isInteger(minimumSdk) || minimumSdk < 1) throw new Error('Gradle must define a valid minSdk.')

const releaseUrl = `https://github.com/PandesalPanpan/Nook/releases/tag/${tag}`
const manifest = {
  schemaVersion: 1,
  versionName,
  versionCode,
  packageName,
  apk: `nook-v${versionName}.apk`,
  sha256,
  minimumSdk,
  publishedAt,
  releaseUrl,
}

const outputPath = resolve(output)
await mkdir(dirname(outputPath), { recursive: true })
await writeFile(outputPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')
