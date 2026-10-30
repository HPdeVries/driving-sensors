#!/usr/bin/env node
// Single source of truth: package.json "version". Rewrites the native and web constants.
// Usage: node scripts/sync-version.mjs [--check]
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const check = process.argv.includes('--check');
const version = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')).version;
if (!/^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/.test(version)) {
  console.error(`package.json version "${version}" is not semver`);
  process.exit(1);
}

const targets = [
  { file: 'android/src/main/kotlin/nl/flexlogic/drivingsensors/Version.kt', re: /(const val PLUGIN_VERSION = ")([^"]*)(")/ },
  { file: 'ios/Sources/DrivingSensorsPlugin/Version.swift', re: /(static let current = ")([^"]*)(")/ },
  { file: 'src/web.ts', re: /(const PLUGIN_VERSION = ')([^']*)(')/ },
];

const mismatches = [];
for (const { file, re } of targets) {
  const path = join(root, file);
  const src = readFileSync(path, 'utf8');
  const m = src.match(re);
  if (!m) {
    mismatches.push(`${file}: version constant not found`);
    continue;
  }
  const current = m[2];
  if (current === version) continue;
  if (check) {
    mismatches.push(`${file}: has ${current}, package.json has ${version}`);
  } else {
    writeFileSync(path, src.replace(re, `$1${version}$3`));
    console.log(`updated ${file}: ${current} -> ${version}`);
  }
}
if (mismatches.length) {
  console.error('Version mismatch (run "npm run version:sync"):\n  ' + mismatches.join('\n  '));
  process.exit(1);
}
console.log(check ? `versions in sync (${version})` : `synced to ${version}`);
