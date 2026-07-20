# Bilingual Documentation Directory Design

> [中文](#中文设计) | [English](#english-design)

## English design

### Goal

Give every project document, except benchmark fixtures, a complete English and Simplified Chinese counterpart. Keep each file single-language for prose, make the language switch obvious at the top, and preserve stable links for readers using the old paths.

### Scope

Included: `README.md`, all Markdown files under `docs/`, and other project-facing Markdown documentation outside `benchmarks/`.

Excluded: `benchmarks/` corpus fixtures, source code, executable configuration, dependency lists, and denylist data. Benchmark text is test input and must not change because translation would alter retrieval behavior and evaluation results.

### Directory model

```text
README.md                 # English default GitHub entry
README.zh-CN.md           # Simplified Chinese entry
docs/
  en/                     # English prose documents
  zh-CN/                  # Chinese counterparts with matching paths
```

Every file in `docs/en/` has exactly one corresponding file in `docs/zh-CN/` with the same relative path and filename. Internal plans and specs are included in this mirror.

### Language switch

Each localized file starts with a compact switch line:

```md
[中文](../zh-CN/path/to/file.md) | **English**
```

The Chinese counterpart reverses emphasis and uses the corresponding relative path. `README.md` and `README.zh-CN.md` use root-relative links. Code blocks, shell commands, API paths, environment variables, identifiers, and proper nouns remain unchanged when translating prose.

### Compatibility

The old `docs/<file>.md` paths become small redirect stubs containing a deprecation note and links to both localized files. This prevents existing README links, bookmarks, and automation references from silently breaking while making the new canonical locations explicit.

### Translation policy

English is the normative technical source. Chinese is a complete translation, not a summary or inline annotation. Headings, paragraphs, tables, lists, warnings, and explanatory prose are translated; executable snippets and literal values are preserved. The project glossary in `.baoyu-skills/baoyu-translate/EXTEND.md` remains the terminology source of truth.

### Validation

The migration is complete only when:

1. Every non-benchmark Markdown source has an English and Chinese counterpart.
2. No canonical English/Chinese prose file contains mixed-language prose beyond code, identifiers, and required literals.
3. Every counterpart has a valid language-switch link.
4. All internal Markdown links resolve to existing files or intentionally external URLs.
5. Benchmark files are byte-for-byte unchanged.
6. A repository scan reports no conflict markers and `git diff --check` passes.

### References

- GitHub presents a repository README from the root, `.github`, or `docs` directory: <https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-readmes>
- ReadMe documents a language picker for localized documentation: <https://docs.readme.com/ent/docs/language-support-and-localization>

## 中文设计

### 目标

除基准测试语料外，为每份项目文档提供完整的英文版本和简体中文版本。每个文件的正文只使用一种语言，在顶部提供明确的语言切换链接，并保留旧路径兼容跳转，避免历史链接失效。

### 范围

纳入：`README.md`、`docs/` 下全部 Markdown，以及 `benchmarks/` 之外面向项目使用者的其他 Markdown 文档。

不纳入：`benchmarks/` 语料、源代码、可执行配置、依赖清单和 denylist 数据。基准文本是测试输入，翻译会改变检索行为和评估结果，因此保持不变。

### 目录模型

```text
README.md                 # GitHub 默认英文入口
README.zh-CN.md           # 简体中文入口
docs/
  en/                     # 英文正文
  zh-CN/                  # 与英文路径一一对应的中文文档
```

`docs/en/` 中的每个文件都必须在 `docs/zh-CN/` 下拥有相同相对路径和文件名的对应文件，内部设计稿和实施计划也包含在镜像中。

### 语言切换

每个本地化文件开头放置简短的切换行。英文文件突出 English，中文文件突出 中文。README 使用根目录相对链接。代码块、Shell 命令、API 路径、环境变量、标识符和专有名词在翻译时保持原样。

### 兼容性

旧的 `docs/<file>.md` 路径改成简短的弃用跳转文件，包含提示以及英文、中文两个新路径的链接。这样可以保护现有 README 链接、书签和自动化引用，同时明确新的规范路径。

### 翻译策略

英文是规范技术来源，中文必须是完整翻译，不能再使用摘要或夹杂式说明。标题、段落、表格、列表、警告和解释性正文都要翻译；可执行代码片段和字面量保持不变。术语以 `.baoyu-skills/baoyu-translate/EXTEND.md` 为准。

### 验收标准

1. 除基准语料外，每份 Markdown 都有英文和中文对应文件。
2. 英文/中文规范正文不混杂另一种语言，代码、标识符和必要字面量除外。
3. 每个对应文件都有有效的语言切换链接。
4. 所有内部 Markdown 链接都指向存在的文件或明确的外部 URL。
5. 基准文件逐字节不变。
6. 仓库扫描无冲突标记，且 `git diff --check` 通过。

