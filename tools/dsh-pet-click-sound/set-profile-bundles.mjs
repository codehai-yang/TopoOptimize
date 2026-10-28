#!/usr/bin/env node
/**
 * 切换 DSH 桌面 profile 的 bundle 列表：把 A 换成 B（保持顺序），可重复执行。
 * 用法：node set-profile-bundles.mjs <profile 目录> <旧包名> <新包名>
 *   例：node set-profile-bundles.mjs "C:\\Users\\yang\\.dsh\\profiles\\desktop" dsh-pet dsh-pet-nhd
 * 说明：只改 package.json 的 dsh.profile.bundles；依赖项由 pnpm 负责。
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const [profile, from, to] = process.argv.slice(2);
if (!profile || !from || !to) {
  console.error('用法：node set-profile-bundles.mjs <profile 目录> <旧包名> <新包名>');
  process.exit(2);
}

const file = join(profile, 'package.json');
const pkg = JSON.parse(readFileSync(file, 'utf8'));
const bundles = pkg?.dsh?.profile?.bundles;
if (!Array.isArray(bundles)) {
  console.error('package.json 里没有 dsh.profile.bundles，放弃修改');
  process.exit(1);
}

const before = bundles.slice();
if (bundles.includes(from)) {
  pkg.dsh.profile.bundles = bundles.map((name) => (name === from ? to : name));
} else if (!bundles.includes(to)) {
  pkg.dsh.profile.bundles = [...bundles, to];
}

writeFileSync(file, JSON.stringify(pkg, null, 2) + '\n');
console.log('bundle 列表：');
console.log('  改前：' + before.join(', '));
console.log('  改后：' + pkg.dsh.profile.bundles.join(', '));
