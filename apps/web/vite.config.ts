import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';
export default defineConfig({plugins: [react(), VitePWA({
  registerType: 'prompt',
  includeAssets: ['figma/*.svg', 'favicon-48.png', 'branding/*.png', 'icons/*.png'],
  manifest: {
    name: 'Nook',
    short_name: 'Nook',
    description: 'Your calm, offline-first second brain',
    theme_color: '#171412',
    background_color: '#171412',
    display: 'standalone',
    start_url: '/',
    icons: [
      {src: '/icons/nook-pwa-192.png', sizes: '192x192', type: 'image/png', purpose: 'any maskable'},
      {src: '/icons/nook-pwa-512.png', sizes: '512x512', type: 'image/png', purpose: 'any maskable'},
    ],
  },
  workbox: {globPatterns: ['**/*.{js,css,html,svg,png,woff2}'], navigateFallback: 'index.html'},
})], build: {chunkSizeWarningLimit: 900}});
