import { describe, expect, it } from 'vitest'
import { normalizeMarkdown } from './normalizeMarkdown'

const architectureInput = `## 绯荤粺鏋舵瀯

璁捐濡備笅锛?**妯″潡鍒掑垎锛氭灦鏋勬寜鍔熻兘妯″潡鍒掑垎锛屽悇妯″潡鑱岃矗娓呮櫚銆?
- 璁よ瘉锛氬井淇＄櫥褰曘€丣WT
- 鍦烘櫙锛氬畼鏂?鑷畾涔夊満鏅?- 寮€鍙戣繘搴?*锛?
1. 0鐨勪富浣撳疄鐜伴樁娈礫5]銆?
## 妯″潡涓庤竟鐣?
| 妯″潡 | 鑱岃矗 | 鍏ュ彛 |
| ------ | ------ | ------ |
| 璁よ瘉 | 寰俊 | routes/auth.js |
| 缂撳瓨 | Redis灏佽 | \`services/redis |
| cache.js\` | [4] |
cache.js\` | 浠ヤ笂涓虹煡璇嗗簱涓彲鑾峰彇鍒扮殑鍏ㄩ儴鏋舵瀯淇℃伅[4]銆?|`

const deployInput = `1. **瀹夎PostgreSQL16**锛屽垱寤烘暟鎹簱 \`postgresql \` 涓?\`speakeasy \`

1. **瀹夎Redis**

1. 鍚姩涓棿浠舵湇鍔?
1. 閰嶇疆鐜鍙橀噺锛岃缃? \`\`\`env
DATABASE_URL=...
\`\`\`

1. 鍚姩鍚庣鏈嶅姟\`\`\`bash
1. ## 鍒濆鍖栨暟鎹簱
cd server
npm run db:migrate
\`\`\`

npminstallnodesrc/index.js`

describe('normalizeMarkdown', () => {
  it('repairs malformed architecture markdown and table artifacts', () => {
    const arch = normalizeMarkdown(architectureInput)

    expect(arch).toContain('## 妯″潡涓庤竟鐣?')
    expect(arch).toContain('| 妯″潡 | 鑱岃矗 | 鍏ュ彛 |')
    expect(arch).toContain('services/redis/cache.js')
    expect(arch).not.toContain('cache.js` |')
  })

  it('repairs deployment steps, code fences and glued commands', () => {
    const deploy = normalizeMarkdown(deployInput)

    expect(deploy).toMatch(/2\.\s+\*\*瀹夎Redis/)
    expect(deploy).toContain('```env')
    expect(deploy).toContain('## 鍒濆鍖栨暟鎹簱')
    expect(deploy).toContain('npminstall')
    expect(deploy).toContain('nodesrc/index.js')
  })

  it('returns empty input unchanged', () => {
    expect(normalizeMarkdown('')).toBe('')
  })

  it('normalizes tables, trees, arrows, inline commands and broken markup', () => {
    const input = [
      '系统架构SpeakEasy服务说明',
      '##模块与边界|模块|职责|入口||---|---|---||API|问答|/api/chat',
      '技术栈|组件||---||React',
      '项目结构├── src└── app',
      '部署流程 前端→API↓Worker',
      '说明：bash npmrundev',
      '-**重点：需要处理',
      '**这是一个很长很长很长很长很长很长很长很长的整段加粗内容，需要去掉整段加粗。**',
      '`  inline  ` 和 `空',
      'npminstall',
    ].join('\n')

    const out = normalizeMarkdown(input)

    expect(out).toContain('## 系统架构')
    expect(out).toContain('## 模块与边界')
    expect(out).toContain('| API | 问答 | /api/chat |')
    expect(out).toContain('## 技术栈')
    expect(out).toContain('```text')
    expect(out).toContain('├── src')
    expect(out).toContain('前端→API↓Worker')
    expect(out).toContain('```bash')
    expect(out).toContain('npm run dev')
    expect(out).toContain('- **重点**：需要处理')
    expect(out).not.toContain('**这是一个很长')
    expect(out).toContain('`inline`')
    expect(out).toContain('空')
  })

  it('repairs prose accidentally embedded in code fences and malformed emphasis', () => {
    const input = [
      '```bash',
      '1. ## 初始化数据库',
      '1. 执行迁移',
      'cd server',
      '```',
      '# 标题',
      '**短语**正文',
      '1. 第一步',
      '',
      '- 子项',
      '1. 第二步',
      'text `',
    ].join('\n')

    const out = normalizeMarkdown(input)

    expect(out).toContain('## 初始化数据库')
    expect(out).toContain('执行迁移')
    expect(out).toContain('cd server')
    expect(out).toContain('## 标题')
    expect(out).toContain('## 短语')
    expect(out).toContain('1. 第一步')
    expect(out).toContain('2. 第二步')
    expect(out).toContain('text')
    expect(out).not.toContain('text `')
  })

  it('covers table preambles and fallback cleanup branches', () => {
    const input = [
      '说明：|列A|列B||---|---||值A|值B',
      'a|b',
      '提示**：内容',
      '孤立**',
      '`成对` 后面 `坏',
      '`a` b `',
    ].join('\n')

    const out = normalizeMarkdown(input)

    expect(out).toContain('说明：')
    expect(out).toContain('列A|列B')
    expect(out).toContain('a|b')
    expect(out).toContain('提示：内容')
    expect(out).toContain('孤立')
    expect(out).toContain('成对')
    expect(out).not.toContain('`a` b `')
  })

  it('covers table footers, existing code trees and arrow skip branches', () => {
    const input = [
      '| 名称 | 路径 |',
      '| --- | --- |',
      '| 缓存 | `services/redis |',
      '| cache.js` |',
      '以上为知识库摘要',
      '```text',
      '项目结构├── src',
      '```',
      '架构|模块|职责||---|---||A|B|C|D|E',
      '流程 前端 → API ↓ Worker [3]#',
    ].join('\n')

    const out = normalizeMarkdown(input)

    expect(out).toContain('services/redis/cache.js')
    expect(out).toContain('以上为知识库摘要')
    expect(out).toContain('项目结构')
    expect(out).toContain('├── src')
    expect(out).toContain('架构|模块|职责')
    expect(out).toContain('[3]')
  })

  it('covers table footer extraction and arrow-table bypass branches', () => {
    const input = [
      '| 说明 | 值 |',
      '| --- | --- |',
      '尾注|以上为知识库摘要[1]',
      '**流程** 阶段|动作|状态||---|---|---||前端→API|提交|成功',
    ].join('\n')

    const out = normalizeMarkdown(input)

    expect(out).toContain('以上为知识库摘要[1]')
    expect(out).toContain('**流程**阶段|动作|状态')
    expect(out).not.toContain('```text\n阶段|动作|状态')
  })

  it('covers path continuations, empty sanitized rows and formatted arrow flows', () => {
    const input = [
      '| 类型 | 路径 | 备注 |',
      '| --- | --- | --- |',
      '| API | services/api/ | 主服务 |',
      '| controller.ts | [2] |',
      '| 知识库 | 以上为摘要 |',
      '部署链路 前端服务调用用户接口→后端服务写入向量库',
    ].join('\n')

    const out = normalizeMarkdown(input)

    expect(out).toContain('services/api/')
    expect(out).toContain('controller.ts')
    expect(out).toContain('[2]')
    expect(out).not.toContain('|  |  |')
    expect(out).toContain('## 部署链')
    expect(out).toContain('```text')
    expect(out).toContain('→ 后端服务写入向量库')
  })
})
