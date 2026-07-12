const crypto = require('node:crypto')

// Node 16 的 node:crypto 默认导出缺少 Vite 5 直接调用的 getRandomValues。
// 这里在 Vite/Vitest 加载前补齐该方法，保持本地验证环境与 Node 18+ 行为一致。
if (typeof crypto.getRandomValues !== 'function') {
  crypto.getRandomValues = crypto.webcrypto.getRandomValues.bind(crypto.webcrypto)
}

if (typeof globalThis.crypto?.getRandomValues !== 'function') {
  globalThis.crypto = crypto.webcrypto
}
