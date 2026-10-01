import { defineConfig } from 'vitest/config';
export default defineConfig({test: {include: ['firebase/**/*.emulator.test.ts'], testTimeout: 15000, hookTimeout: 30000, fileParallelism: false}});
