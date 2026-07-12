/// <reference types="vitest" />
import path from 'path'
import { webcrypto } from 'crypto'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Node 16 本地验证环境的 globalThis.crypto 可能存在但缺少 getRandomValues。
if (typeof globalThis.crypto?.getRandomValues !== 'function') {
  globalThis.crypto = webcrypto as unknown as typeof globalThis.crypto
}

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    setupFiles: ['./src/test/setup.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json-summary', 'lcov'],
      include: ['src/api/**/*.{ts,tsx}', 'src/lib/**/*.{ts,tsx}'],
      exclude: [
        'src/main.tsx',
        'src/vite-env.d.ts',
        'src/**/*.test.{ts,tsx}',
        'src/lib/__*.mjs',
        'src/components/ui/**',
      ],
      thresholds: {
        lines: 95,
        functions: 95,
        branches: 90,
        statements: 95,
      },
    },
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/actuator': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})
