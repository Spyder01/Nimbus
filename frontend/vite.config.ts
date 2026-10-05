import path from 'node:path'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // Proxy the Spring backend so the browser sees one origin (session + CSRF cookies just work,
    // and the GitHub callback http://localhost:5173/login/oauth2/code/github reaches Spring).
    proxy: Object.fromEntries(
      ['/api', '/oauth2', '/login/oauth2', '/logout'].map((p) => [p, 'http://localhost:8080']),
    ),
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
})
