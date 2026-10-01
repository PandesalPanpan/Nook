import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';
export default defineConfig({plugins: [react(), VitePWA({registerType:'prompt', includeAssets:['figma/*.svg'], manifest:{name:'Nook',short_name:'Nook',description:'Your calm, offline-first second brain',theme_color:'#171412',background_color:'#171412',display:'standalone',start_url:'/',icons:[{src:'/figma/3-154-imgEllipse.svg',sizes:'any',type:'image/svg+xml',purpose:'any'}]},workbox:{globPatterns:['**/*.{js,css,html,svg,woff2}'],navigateFallback:'index.html'}})],build:{chunkSizeWarningLimit:900}});
