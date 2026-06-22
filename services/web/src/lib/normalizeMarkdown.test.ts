import { normalizeMarkdown } from './normalizeMarkdown'

const architectureInput = `## 系统架构

设计如下：-**模块划分：架构按功能模块划分，各模块职责清晰。

- 认证：微信登录、JWT
- 场景：官方/自定义场景
- 开发进度**：

1. 0的主体实现阶段[5]。

## 模块与边界

| 模块 | 职责 | 入口 |
| ------ | ------ | ------ |
| 认证 | 微信 | routes/auth.js |
| 缓存 | Redis封装 | \`services/redis |
| cache.js\` | [4] |
cache.js\` | 以上为知识库中可获取到的全部架构信息[4]。 |`

const deployInput = `1. **安装PostgreSQL16**，创建数据库 \`postgresql \` 与 \`speakeasy \`

1. **安装Redis**

1. 启动中间件服务

1. 配置环境变量，设置: \`\`\`env
DATABASE_URL=...
\`\`\`

1. 启动后端服务\`\`\`bash
1. ## 初始化数据库
cd server
npm run db:migrate
\`\`\`

npminstallnodesrc/index.js`

function assert(condition: boolean, msg: string) {
  if (!condition) {
    console.error('FAIL:', msg)
    process.exit(1)
  }
}

const arch = normalizeMarkdown(architectureInput)
assert(arch.includes('- **模块划分**：'), 'list+bold mash')
assert(!arch.includes('开发进度**'), 'orphan bold')
assert(arch.includes('services/redis/cache.js'), 'merged table path')
assert(!arch.includes('cache.js` |'), 'broken table footer')

const deploy = normalizeMarkdown(deployInput)
assert(/2\.\s+\*\*安装Redis/.test(deploy), 'renumber list items')
assert(deploy.includes('```env'), 'env code fence')
assert(deploy.includes('## 初始化数据库'), 'heading outside code block')
assert(deploy.includes('npm install'), 'unmangle npm install')
assert(deploy.includes('node src/index.js'), 'unmangle node command')

console.log('ALL TESTS PASSED')
console.log('--- deploy sample ---')
console.log(deploy)
