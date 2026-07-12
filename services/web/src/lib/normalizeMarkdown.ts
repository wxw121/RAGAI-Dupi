/**
 * 规范化 LLM 返回的“近似 Markdown”文本。
 *
 * 后端 RAG 会要求模型输出 Markdown，但模型常把标题空格、代码围栏、表格行、
 * 引用编号和有序列表混在一起。本模块采用一组小型修复策略按顺序清洗文本，
 * 让前端渲染层接收到更接近标准 Markdown 的内容。
 */

const CODE_LANGS = 'bash|sh|shell|env|sql|json|javascript|js|nginx|yaml|yml|text'

const SECTION_TITLES = [
  '架构概览',
  '系统架构',
  '技术栈',
  '模块划分',
  '模块与边界',
  '核心设计决策',
  '核心设计',
  '推荐生产部署架构',
  '生产部署',
  '项目结构',
  '目录结构',
  '本地开发环境搭建',
  '本地部署',
  '部署步骤',
  '环境准备',
  '初始化数据库',
  '启动服务',
]

/** 拆开粘连的 shell/sql 命令，保证代码块中的命令逐行可读。 */
function unmangleCommands(text: string): string {
  return text
    .replace(/\bcdserver\b/gi, 'cd server')
    .replace(/\bcd\s*server\b/gi, 'cd server')
    .replace(/\bnpminstall\b/gi, 'npm install')
    .replace(/\bnodesrc\//gi, 'node src/')
    .replace(/\bnode(src\/)/gi, 'node $1')
    .replace(/\bnpm\s*install\b/gi, 'npm install')
    .replace(/\bnpmrundb:migrate\b/gi, 'npm run db:migrate')
    .replace(/\bnpmrundev\b/gi, 'npm run dev')
    .replace(/\bnpmrun(\w+)\b/gi, 'npm run $1')
    .replace(/\bnpm\s*run\s*(\w+)\b/gi, 'npm run $1')
    .replace(/\bsqlCREATE\b/gi, 'CREATE')
    .replace(/\bCREATE\s*DATABASE\b/gi, 'CREATE DATABASE')
    .replace(/([;{}])([A-Za-z])/g, '$1\n$2')
    .replace(/(\w)(cd\s)/gi, '$1\n$2')
    .replace(/(server)(npm)/gi, '$1\n$2')
    .replace(/(install)(node)/gi, '$1\n$2')
    .replace(/(WSS)(Nginx)/gi, '$1\n$2')
    .replace(/(代理)(Node)/gi, '$1\n$2')
}

function fixGluedCodeFences(text: string): string {
  const openLang = new RegExp(`\`\`\`(${CODE_LANGS})(?!\\n)`, 'gi')
  let s = text
    .replace(/([^\n`])(```(?:bash|sh|shell|env|sql|json|javascript|js|nginx|yaml|yml|text))/gi, '$1\n\n$2')
    .replace(/([：:])\s*(```)/g, '$1\n\n$2')
    .replace(openLang, '```$1\n')
    .replace(/([^\n`])(```)(?!\w)/g, '$1\n$2')
  return s
}

function fixCodeFences(text: string): string {
  const gluedLine = new RegExp(`^\`\`\`(${CODE_LANGS})(\\S[^\\n]*)$`, 'gim')

  let s = text.replace(gluedLine, (_, lang: string, body: string) => {
    const cleaned = unmangleCommands(body.trim())
    return `\`\`\`${lang.toLowerCase()}\n${cleaned}\n\`\`\``
  })

  s = s.replace(
    new RegExp(`\`\`\`(${CODE_LANGS})[ \\t]+([^\\n]+)`, 'gi'),
    (_, lang: string, body: string) =>
      `\`\`\`${lang.toLowerCase()}\n${unmangleCommands(body.trim())}\n\`\`\``,
  )

  return s
}

/** 将误塞进 shell/env 代码块里的标题和编号说明抽出，避免 prose 被当作命令渲染。 */
function splitMarkdownInsideCodeBlocks(text: string): string {
  return text.replace(/```(\w+)\n([\s\S]*?)```/g, (match, lang, body) => {
    if (!/##\s|^\d+\.\s/m.test(body)) return match

    const codeLines: string[] = []
    const proseLines: string[] = []

    for (const line of body.split('\n')) {
      const heading = line.match(/^\d+\.\s*(##\s.+)$/) || line.match(/^(##\s.+)$/)
      if (heading) {
        proseLines.push(heading[1])
      } else if (/^\d+\.\s+[\u4e00-\u9fff]/.test(line) && !/[`$]/.test(line)) {
        proseLines.push(line.replace(/^\d+\.\s+/, ''))
      } else {
        codeLines.push(line)
      }
    }

    const code = unmangleCommands(codeLines.join('\n').trim())
    const prose = proseLines.join('\n\n').trim()
    const parts: string[] = []
    if (prose) parts.push(prose)
    if (code) parts.push(`\`\`\`${lang}\n${code}\n\`\`\``)
    return parts.length ? parts.join('\n\n') : match
  })
}

/** 清理游离 #、修复 [1]# 引用标记，并把项目符号统一成 Markdown 列表。 */
function stripArtifacts(text: string): string {
  return text
    .replace(/\[(\d+)\]#/g, '[$1]')
    .replace(/([^\s#])#\s*$/gm, '$1')
    .replace(/\s+#\s*$/gm, '')
    .replace(/\/#\s*$/gm, '/')
    .replace(/^-{3,}\s*#?\s*$/gm, '')
    .replace(/^#\s*$/gm, '')
    .replace(/。\s*#\s*/g, '。\n\n')
    .replace(/•\s*/g, '- ')
}

/** 拆开粘连章节标题，例如“系统架构SpeakEasy”会被拆成二级标题和正文。 */
function splitGluedSectionTitles(text: string): string {
  let s = text
  for (const title of SECTION_TITLES) {
    const re = new RegExp(`(^|[^#\\n])(${title})(?=[A-Za-z\\u4e00-\\u9fff|])`, 'g')
    s = s.replace(re, `$1\n\n## ${title}\n\n`)
  }
  return s
}

/**
 * 展开单行压缩表格，例如“技术栈|a|b||---|---||x|y”。
 * 如果第一个竖线前粘着标题，也会提取为独立标题。
 */
function expandMashedTable(segment: string): string {
  const trimmed = segment.trim()
  if (!trimmed.includes('|')) return trimmed

  const pipeCount = (trimmed.match(/\|/g) || []).length
  if (pipeCount < 3) return trimmed

  let title = ''
  let preamble = ''
  let tablePart = trimmed

  const gluedTitle = trimmed.match(/^([\u4e00-\u9fffA-Za-z]{2,12})(\|.+)$/)
  if (gluedTitle && (gluedTitle[2].includes('----') || gluedTitle[2].includes('||'))) {
    title = gluedTitle[1]
    tablePart = gluedTitle[2]
  }

  const preambleMatch = tablePart.match(/^(.+?[：:]\s*(?:\[\d+\]\s*)?)((?:\|[^|]*)+\|\|.+)$/)
  if (preambleMatch) {
    preamble = preambleMatch[1].trim()
    tablePart = preambleMatch[2]
  }

  const rows = tablePart
    .split(/\|\|/)
    .map((row) => row.trim())
    .filter(Boolean)
    .map((row) => {
      let r = row.replace(/\[(\d+)\]#?/g, '[$1]')
      const cells = r
        .replace(/^\|+/, '')
        .replace(/\|+$/, '')
        .split('|')
        .map((cell) => sanitizeTableCell(cell.trim()))
      return `| ${cells.join(' | ')} |`
    })

  if (rows.length < 2) return trimmed

  const table = rows.join('\n')
  const head = title ? `## ${title}\n\n` : ''
  const intro = preamble ? `${preamble}\n\n` : ''
  return `${head}${intro}${table}`
}

function sanitizeTableCell(cell: string): string {
  let c = cell.replace(/`/g, '')
  if (/^以上为/.test(c) || /^知识库/.test(c)) return ''
  return c
}

/** 把“##模块与边界|模块|职责|入口||---...”修复为标题 + GFM 表格。 */
function fixHeadingPrefixedTables(text: string): string {
  return text.replace(
    /^(#{1,6})\s*([^|\n#]+)\|([^\n]+(?:\|\|[^\n]+)+)$/gm,
    (_, hashes, title, tableBlob) => {
      const table = expandMashedTable(`|${tableBlob}`)
      return `${hashes} ${title.trim()}\n\n${table}`
    },
  )
}

/** 在普通文本中查找并展开被压缩成一行的表格片段。 */
function fixSquashedTables(text: string): string {
  const tableBlob =
    /[\u4e00-\u9fff\w][\u4e00-\u9fff\w\s]{0,14}\|[^|\n]+(?:\|\|[^|\n]+){2,}/g

  return text.replace(tableBlob, (match) => expandMashedTable(match))
}

/** 合并被错误断开的表格行，并把混入表格的尾注移出表格。 */
function fixBrokenTableRows(text: string): string {
  const lines = text.split('\n')
  const merged: string[] = []

  for (const line of lines) {
    const trimmed = line.trim()
    const isTableRow = /^\|.+\|$/.test(trimmed)

    if (isTableRow && merged.length > 0 && /^\|.+\|$/.test(merged[merged.length - 1].trim())) {
      const prev = merged[merged.length - 1]
      const prevCells = prev.split('|').map((c) => c.trim()).filter(Boolean)
      const cells = line.split('|').map((c) => c.trim()).filter(Boolean)
      const prevLast = prevCells[prevCells.length - 1] || ''
      const openTick = (prevLast.match(/`/g) || []).length % 2 !== 0

      if (
        prevCells.length >= 2 &&
        cells.length >= 1 &&
        (openTick || /\/$/.test(prevLast)) &&
        /^[a-zA-Z0-9_./`-]+$/.test(cells[0].replace(/`/g, '')) &&
        !/^-+$/.test(cells[0])
      ) {
        const base = prev.replace(/\|\s*$/, '')
        const lastIdx = base.lastIndexOf('|')
        const prefix = base.slice(0, lastIdx + 1)
        const lastCell = prevLast.replace(/`/g, '')
        const nextPart = cells[0].replace(/`/g, '')
        const joiner = lastCell.endsWith('/') ? '' : '/'
        const tail = cells.length > 1 ? ` | ${cells.slice(1).join(' | ')} |` : ' |'
        merged[merged.length - 1] = `${prefix} ${lastCell}${joiner}${nextPart}${tail}`
        continue
      }
    }

    if (!isTableRow && merged.length > 0 && /^\|.+\|$/.test(merged[merged.length - 1].trim())) {
      const cells = line.split('|').map((c) => c.trim())
      if (cells.some((c) => /^以上为/.test(c))) {
        const footer = cells.find((c) => /以上为/.test(c)) || line.replace(/`/g, '').replace(/\|/g, '').trim()
        merged.push('')
        merged.push(footer)
        continue
      }
    }

    merged.push(line)
  }

  const out: string[] = []

  for (const raw of merged) {
    if (!/^\|.+\|$/.test(raw.trim())) {
      out.push(raw)
      continue
    }

    let line = raw
      .split('|')
      .map((part, idx, arr) => {
        if (idx === 0 || idx === arr.length - 1) return part
        return ` ${sanitizeTableCell(part.trim())} `
      })
      .join('|')

    if (line.replace(/\|/g, '').trim() === '') continue
    out.push(line)
  }

  for (let i = 0; i < out.length; i++) {
    const line = out[i]
    if (!line.includes('|')) continue
    const cells = line.split('|').map((c) => c.trim())
    if (cells.some((c) => /^以上为/.test(c))) {
      const footer = cells.find((c) => /以上为/.test(c)) || ''
      out[i] = ''
      if (footer) out.splice(i + 1, 0, '', footer.replace(/`/g, ''))
      break
    }
  }

  return out.filter((l, i, arr) => !(l === '' && arr[i + 1] === '')).join('\n')
}

/** 将目录树符号（├── / └──）包进 text 代码块，保留层级缩进。 */
function fixDirectoryTrees(text: string): string {
  return text.replace(/([^\n`]*(?:├──|└──)[^\n`]+)/g, (block) => {
    if (block.includes('```')) return block

    let tree = block.replace(/([^\n])(├──|└──)/g, '$1\n$2')
    tree = tree.replace(/(\S)(├──|└──)/g, '$1\n$2')
    tree = tree
      .split('\n')
      .map((l) => l.replace(/#\s*$/, '').replace(/\[(\d+)\]#/g, '[$1]'))
      .join('\n')

    return `\n\`\`\`text\n${tree.trim()}\n\`\`\`\n`
  })
}

/** 将带箭头的部署链路格式化为预排版文本块，保留流程方向。 */
function fixArrowFlows(text: string): string {
  return text.replace(
    /\*{0,2}([\u4e00-\u9fffA-Za-z]{2,20})\*{0,2}\s*([\u4e00-\u9fff\w][^\n。]{10,}?(?:→|↓)[^\n。]{5,})/g,
    (_, heading, flow) => {
      if (flow.includes('```') || (flow.includes('|') && flow.split('|').length > 4)) {
        return `**${heading}**${flow}`
      }

      const formatted = flow
        .replace(/\[(\d+)\]#?/g, '[$1]')
        .replace(/\s*↓\s*/g, '\n↓\n')
        .replace(/\s*→\s*/g, '\n→ ')

      return `## ${heading}\n\n\`\`\`text\n${formatted.trim()}\n\`\`\``
    },
  )
}

function fixNestedSectionNumbers(text: string): string {
  let s = text.replace(/^\d+\.\s+0([\u4e00-\u9fff].*)$/gm, '- 0$1')
  s = s.replace(/^\d+\.\s*\d+\s+([^\n]+)/gm, '## $1')
  return s
}

function fixShortHeadings(text: string): string {
  return text
    .replace(/^#{1,6}\s*([\u4e00-\u9fffA-Za-z]{1,10}[:：])\s*$/gm, '**$1**')
    .replace(/^# ([^\n]+)$/gm, '### $1')
    .replace(/\*\*([\u4e00-\u9fffA-Za-z]{2,20})\*\*(?=[\u4e00-\u9fff\w])/g, '## $1\n\n')
}

/** 修复“-**标题：”这类列表加粗粘连，以及孤立的 ** 标记。 */
function fixListBoldMash(text: string): string {
  return text
    .replace(/([：:])-\*\*([^：\n]+)：/g, '$1\n\n- **$2**：')
    .replace(/^-\s+\*\*([^：:\n]{2,20})[：:]\s*/gm, '- **$1**：')
    .replace(/^-\*\*([^*\n]+)\*\*：/gm, '- **$1**：')
    .replace(/^-\*\*([^：\n]+)：/gm, '- **$1**：')
    .replace(/([^\n])-\*\*([^：\n]+)：/g, '$1\n\n- **$2**：')
}

function fixOrphanBold(text: string): string {
  return text
    .split('\n')
    .map((line) => {
      const count = (line.match(/\*\*/g) || []).length
      if (count % 2 === 0) return line
      if (/\*\*([：:])/.test(line) && !line.includes('**', line.indexOf('**') + 2)) {
        return line.replace(/\*\*([：:])/g, '$1')
      }
      if (line.endsWith('**')) return line.slice(0, -2)
      return line
    })
    .join('\n')
}

/**
 * 重新编号有序列表；LLM 经常把每一步都输出成“1.”。
 * 空行和子项目符号仍保留在同一个列表语义块中。
 */
function renumberOrderedLists(text: string): string {
  const lines = text.split('\n')
  const out: string[] = []
  let counter = 0
  let inList = false

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const ordered = line.match(/^(\d{1,2})\.\s+(.*)$/)

    if (ordered) {
      if (!inList) {
        counter = 1
        inList = true
      } else {
        counter += 1
      }
      out.push(`${counter}. ${ordered[2]}`)
      continue
    }

    if (
      inList &&
      (line.trim() === '' ||
        /^[-*]\s/.test(line) ||
        /^  +/.test(line) ||
        /^`/.test(line.trim()))
    ) {
      out.push(line)
      continue
    }

    inList = false
    counter = 0
    out.push(line)
  }

  return out.join('\n')
}

function extractInlineCommands(text: string): string {
  return text.replace(
    /([：:])\s*(bash|npm|cd|sql|CREATE|export|pm2|nginx)([^\n。；]+)/gi,
    (_, sep, cmd, rest) => {
      const body = unmangleCommands(`${cmd}${rest}`.trim())
      const lang = /^(sql|CREATE)/i.test(cmd) ? 'sql' : 'bash'
      return `${sep}\n\n\`\`\`${lang}\n${body}\n\`\`\``
    },
  )
}

/** 清理行内代码两侧空格，并删除空的行内代码片段。 */
function fixInlineCode(text: string): string {
  const lines = text.split('\n')
  return lines
    .map((line) => {
      if (line.trim().startsWith('```')) return line
      return line.replace(/`([^`\n]+)`/g, (_, inner: string) => {
        const trimmed = inner.trim()
        return trimmed ? `\`${trimmed}\`` : trimmed
      })
    })
    .join('\n')
}

/** 移除整行/整段加粗，只保留短关键词强调，避免回答整体过度加粗。 */
function fixExcessiveBold(text: string): string {
  return text
    .replace(/\*\*([^*\n]{40,})\*\*/g, '$1')
    .replace(/\*\*([^*]+[。！？][^*]*)\*\*/g, '$1')
}

/** 在非代码围栏区域逐行闭合或移除不成对反引号。 */
function fixBrokenBackticks(text: string): string {
  const lines = text.split('\n')
  let inFence = false
  const out: string[] = []

  for (const line of lines) {
    const trimmed = line.trim()
    if (trimmed.startsWith('```')) {
      out.push(line)
      if (/^```\w+/.test(trimmed)) inFence = true
      else if (trimmed === '```') inFence = false
      continue
    }
    if (inFence) {
      out.push(line)
      continue
    }

    const count = (line.match(/`/g) || []).length
    if (count % 2 === 0) {
      out.push(line)
      continue
    }
    const stripped = line.replace(/`([^`\n]+)$/, '$1').replace(/^([^`\n]+)`/, '$1')
    if ((stripped.match(/`/g) || []).length % 2 === 0) {
      out.push(stripped)
    } else {
      out.push(line.replace(/`/g, ''))
    }
  }

  return out.join('\n')
}

/** 修复模型把 Python venv 命令拆成列表/半截行内代码的问题。 */
function fixSplitPythonVenvCommands(text: string): string {
  return text
    .replace(/`python\s*\n\s*[-*]\s*mvenv\.venv`/gi, '`python -m venv .venv`')
    .replace(/`python\s*\n\s*[-*]\s*m\s*venv\s+\.?venv`/gi, '`python -m venv .venv`')
    .replace(/`py\s*\n\s*(?:3|1)\.\s*12\s*\n\s*[-*]\s*mvenv\.venv`/gi, '`py -3.12 -m venv .venv`')
    .replace(/`py\s*\n\s*(?:3|1)\.\s*12\s*\n\s*[-*]\s*m\s*venv\s+\.?venv`/gi, '`py -3.12 -m venv .venv`')
    .replace(/`py\s*-\s*\n\s*\d+\.\s*12\s+-m\s+venv\s+\.?venv`/gi, '`py -3.12 -m venv .venv`')
    .replace(/`py\s*-\s*\n\s*\d+\.\s*12\s+-mvenv\.venv`/gi, '`py -3.12 -m venv .venv`')
    .replace(/名为\.venv`/g, '名为 `.venv`')
    .replace(/名为\.venv/g, '名为 `.venv`')
}

/** 修复“1. 4venv的优缺点”或“## 3.4 venv 的优缺点”这类片段编号污染的小节标题。 */
function fixMalformedNumberedHeadings(text: string): string {
  return text
    .replace(
      /^\d+\.\s*\d+([A-Za-z][^\n]*(?:优缺点|步骤|说明|用法|配置)[^\n]*)$/gm,
      '## $1',
    )
    .replace(
      /^##\s+\d+\.\d+\s+([A-Za-z][^\n]*(?:优缺点|步骤|说明|用法|配置)[^\n]*)$/gm,
      '## $1',
    )
    .replace(
      /^#{3,6}\s+\d+\.\d+\s+([A-Za-z][^\n]*(?:优缺点|步骤|说明|用法|配置)[^\n]*)$/gm,
      '## $1',
    )
    .replace(
      /^#{2,3}\s*\n\d+\.\s*\d+\s+([A-Za-z][^\n]*(?:优缺点|步骤|说明|用法|配置)[^\n]*)$/gm,
      '## $1',
    )
}

function removeEmptyListItems(text: string): string {
  return text.replace(/^\s*[-*]\s*$/gm, '')
}

function fixDanglingBoldMarkers(text: string): string {
  return text.replace(/^- ([^*\n：:]{2,30})\*{2,}([：:])/gm, '- **$1**$2')
}

function fixMalformedVenvAnswerLines(text: string): string {
  return text
    .replace(/^- \*\*创建虚拟环境：\s*使用`?python -m venv \.venv`?/gm, '- **创建虚拟环境**：使用`python -m venv .venv`')
    .replace(/^- 指定Python版本\*+：/gm, '- **指定Python版本**：')
    .replace(/^- 指定Python版本\*+：\s*可以通过`?py -3\.12 -m venv \.venv`?/gm, '- **指定Python版本**：可以通过`py -3.12 -m venv .venv`')
    .replace(/^- 指定Python版本：\s*可以通过`?py -3\.12 -m venv \.venv`?/gm, '- **指定Python版本**：可以通过`py -3.12 -m venv .venv`')
    .replace(/^- \*\*指定Python版本\*\*：\s*可以通过`?py -3\.12 -m venv \.venv`?/gm, '- **指定Python版本**：可以通过`py -3.12 -m venv .venv`')
    .replace(/^(\d+)\.\s*12\s*版本来创建环境/gm, '3.12 版本来创建环境')
    .replace(/^##\s+版本来创建环境/gm, '3.12 版本来创建环境')
    .replace(/`py -\s*\n\d+\.\s*12 -m venv \.venv`/g, '`py -3.12 -m venv .venv`')
    .replace(/通过`py -\s*\n\d+\.\s*12 -m venv \.venv`/g, '通过`py -3.12 -m venv .venv`')
}

/** 将散落在正文中的粘连命令行包成 bash 代码块。 */
function fixLooseCommandLines(text: string): string {
  return text.replace(
    /^(npminstall|nodesrc\/|cdserver|npmrun\w+)([^\n]*)$/gim,
    (_, cmd, rest) => `\`\`\`bash\n${unmangleCommands(`${cmd}${rest}`)}\n\`\`\``,
  )
}

export function normalizeMarkdown(text: string): string {
  if (!text) return text

  let s = text.replace(/\r\n/g, '\n')

  s = stripArtifacts(s)
  s = fixSplitPythonVenvCommands(s)
  s = fixMalformedVenvAnswerLines(s)
  s = splitGluedSectionTitles(s)
  s = fixListBoldMash(s)
  s = fixOrphanBold(s)
  s = fixShortHeadings(s)
  s = fixNestedSectionNumbers(s)
  s = fixMalformedNumberedHeadings(s)

  s = s.replace(/([^\n#])(#{1,6})\s*([^\n#]+)/g, (_, before, hashes, title) => {
    const trimmed = title.trim()
    return trimmed ? `${before}\n\n${hashes} ${trimmed}` : `${before}\n\n${hashes}`
  })

  s = s.replace(/^(#{1,6})\s*([^\s#].*)$/gm, (_, hashes, title) => `${hashes} ${title.trim()}`)
  s = fixHeadingPrefixedTables(s)
  s = fixSquashedTables(s)
  s = fixBrokenTableRows(s)

  s = fixDirectoryTrees(s)
  s = fixArrowFlows(s)
  s = fixGluedCodeFences(s)
  s = extractInlineCommands(s)
  s = fixCodeFences(s)
  s = splitMarkdownInsideCodeBlocks(s)
  s = fixLooseCommandLines(s)
  s = fixInlineCode(s)
  s = fixSplitPythonVenvCommands(s)
  s = fixMalformedVenvAnswerLines(s)
  s = fixBrokenBackticks(s)
  s = fixSplitPythonVenvCommands(s)
  s = fixMalformedVenvAnswerLines(s)

  s = s.replace(/([^\n\d])(\d{1,2})\.(?!\d)\s*/g, '$1\n$2. ')
  s = s.replace(/^(\d{1,2})\.(?!\d)([^\s])/gm, '$1. $2')
  s = fixMalformedNumberedHeadings(s)
  s = fixSplitPythonVenvCommands(s)
  s = fixMalformedVenvAnswerLines(s)

  s = s.replace(/([。）\]\w\u4e00-\u9fff])(-\s*)([^\s\-])/g, '$1\n$2$3')
  s = s.replace(/^-([^\s\-])/gm, '- $1')
  s = removeEmptyListItems(s)

  s = renumberOrderedLists(s)
  s = fixSplitPythonVenvCommands(s)
  s = fixMalformedVenvAnswerLines(s)
  s = removeEmptyListItems(s)
  s = fixExcessiveBold(s)
  s = fixDanglingBoldMarkers(s)
  s = fixMalformedVenvAnswerLines(s)
  s = s.replace(/^#\s+([^#\n].*)$/gm, '## $1')
  s = s.replace(/\n{3,}/g, '\n\n')

  return s.trim()
}
