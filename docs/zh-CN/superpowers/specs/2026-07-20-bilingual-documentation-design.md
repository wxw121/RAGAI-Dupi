<!-- language-switch -->
[English](../../../en/superpowers/specs/2026-07-20-bilingual-documentation-design.md)

双语文档目录设计

> [中文](#中文设计) | [English](#english-design)

英语设计

# # #目标

给每个项目文件，除了基准夹具，一个完整的英文和简体中文副本。将每个文件保持为散文的单一语言，使语言切换在顶部明显，并为使用旧路径的读者保留稳定的链接。

# # #范围

包括:“README。‘ docs/ ’下的所有Markdown文件，以及‘ benchmark / ’之外的其他面向项目的Markdown文档。

排除：‘ benchmark / ’语料库fixture、源代码、可执行配置、依赖项列表和denylist数据。基准文本是测试输入，不能更改，因为翻译会改变检索行为和评估结果。

目录模型

```text

README.md                 # English default GitHub entry
README.zh-CN.md           # Simplified Chinese entry
docs/
  en/                     # English prose documents
  zh-CN/                  # Chinese counterparts with matching paths

```

‘ docs/zh / ’中的每个文件在‘ docs/zh-CN/ ’中都有一个对应的文件，具有相同的相对路径和文件名。内部计划和规格包括在这个镜子。

语言切换

每个本地化文件都以一个紧凑的switch行开头：

```md


```

中文的对应物颠倒了重点，使用了相应的相对路径。的自述。和“README.zh-CN”。不要使用根相关链接。代码块、shell命令、API路径、环境变量、标识符和专有名词在翻译散文时保持不变。

# # #的兼容性

旧的“docs/<file>. conf”文件。Md的路径变成了小的重定向存根，其中包含一个弃用说明和指向两个本地化文件的链接。这可以防止现有的README链接、书签和自动化引用在明确指定新的规范位置时无声地中断。

翻译策略

英语是规范的技术来源。中文是一个完整的翻译，而不是摘要或内联注释。标题、段落、表格、列表、警告和解释性散文都要翻译；可执行代码段和文字值将被保留。.baoyu-skills/baoyu-translate/EXTEND中的项目词汇表。Md仍然是真理的术语来源。

# # #验证

只有在满足以下条件时，迁移才算完成：

1. 每个非基准Markdown源都有英文和中文对应版本。
2. 除了代码、标识符和必需的文字外，没有规范的英语/中文散文文件包含混合语言散文。
3. 每个对应物都有一个有效的语言切换链接。
4. 所有内部Markdown链接都解析为现有文件或有意的外部url。
5. 基准测试文件是逐字节不变的。
6. 存储库扫描报告没有冲突标记，并且“git diff—check”通过。

# # #引用

- GitHub提供了一个来自根目录的存储库README。Github ‘，或’ docs '目录：<https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-readmes>
ReadMe文档是本地化文档的语言选择器：<https://docs.readme.com/ent/docs/language-support-and-localization>

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
