import { defineConfig } from '@playwright/test';
export default defineConfig({testDir:'apps/web/e2e',fullyParallel:false,workers:1,use:{baseURL:'http://127.0.0.1:4173',viewport:{width:1440,height:900},trace:'retain-on-failure'},webServer:{command:'npm run preview --workspace @nook/web -- --port 4173',url:'http://127.0.0.1:4173',reuseExistingServer:false},timeout:45000});
