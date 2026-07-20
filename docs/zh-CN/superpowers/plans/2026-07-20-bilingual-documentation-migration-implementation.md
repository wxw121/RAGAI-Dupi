# 双语文档迁移实施计划

<!-- language-switch -->
[English](../../../en/superpowers/plans/2026-07-20-bilingual-documentation-migration-implementation.md)




> **对于代理工作者：**需要的子技能：使用$superpower- execution -plans逐个任务地执行此计划。步骤使用复选框（' -[]'）语法通过update_plan进行跟踪。

**目标：**将所有非基准Markdown文档拆分为完整的英文和简体中文副本，具有文件顶部语言切换和向后兼容的遗留路径。

**架构：** Treat ' README。md ‘和’ docs/en/ ‘作为规范的英文来源，将它们镜像到’ README.zh-CN '下。并替换旧的“docs/*. md”和“docs/zh-CN/”。Md带有重定向存根的路径。在代码块、命令、API标识符、环境变量和基准fixture作为测试输入的地方逐字节保存它们。

**技术栈：** Markdown， PowerShell库扫描，Git相对链接，现有的' .baoyu-skills/baoyu-translate/EXTEND。医学的术语表。

---

任务1：盘点并冻结迁移清单

* *文件:* *
-创建：“scripts/docs/ bilinguual -manifest.ps1”
-阅读：自述。Md ‘，所有’ docs/**/*。Md ‘，所有非基准’ *.md '
-测试：“基准/v1.3/corpus/*”

-[] **步骤1：定义清单输入和排除

脚本必须枚举git跟踪的“README”。md ‘和’ docs/ ‘下的Markdown文件，排除所有以’ benchmark / ‘开头的路径，并为每个带有’ source ', ‘ english ‘, ‘ chinese ’和’ legacy ’路径的源发出一个TSV行。映射保持‘ docs/en/ ’和‘ docs/zh-CN/ ’下的相对路径不变。

-[] **步骤2：运行清单记录计数**

运行powershell -NoProfile -File scripts/docs/bilingual-manifest。ps1 -Root。，并期望每个包含的Markdown文档有一个确定性行，并且零基准行。

-[] **步骤3：验证基准不变性基线**

运行git -c safe。目录=D:/software/cld_project/ dpi - rag ls-files benchmark | ForEach-Object {git hash-object $_} '，并将输出保存在库外，以便迁移后进行比较；没有基准测试路径可能出现在阶段性差异中。

任务2：创建规范的目录布局和语言切换约定

* *文件:* *
-创建：‘ README.zh-CN.md ’
-创建：‘ docs/en/README.md ’
-创建：‘ docs/zh-CN/README.md ’
-创建：‘ docs/en/.language-switch.md ’
-创建：‘ docs/zh-CN/.language-switch.md ’
-修改：‘ README.md ’

-[] **第一步：将英文README内容移动到规范位置**

  Copy the current English README prose to `docs/en/README.md`, preserve commands/tables exactly, and add `[中文](../../../../README.md) | **English**` immediately after the H1.

-[] **第二步：使根README成为一个稳定的英语入口点**

  Keep `README.md` as the GitHub default English entry, add `[中文](../../../../README.md) | **English**` after the H1, and update every relative documentation link to point to `docs/en/...`.

-[] **第三步：创建中文的README副本**

  Translate the complete README into Simplified Chinese while preserving all code blocks, commands, identifiers, environment variable names, API routes, table values, and links. Add `[中文](../../../../README.md) | [English](../../../../README.md)` after the H1.

-[] **步骤4：添加可重复使用的交换机约定备注**

将准确的链接模式和翻译规则放在两个‘ .language-switch ’中。Md的文件，以便将来的贡献者使用相同的结构；这些是贡献者指导文件，必须有对应的英文/中文文件。

任务3：迁移和翻译面向用户的文档

* *文件:* *
为docs/architecture创建“docs/en/”和“docs/zh-CN/”目录下的镜像文件。医学博士”、“docs /决定。医学博士”、“docs / e2e-testing。医学博士”、“docs /进展。医学博士”、“docs /待办事项。医学博士”、“docs / v1.3-release-runbook。医学博士”、“docs / v1.4-recovery-runbook。医学博士”、“docs / v1.4.1-release-runbook。医学博士”、“docs / v1.4.2-governance-ops-runbook。医学博士”、“docs / v1.5-release-notes。和“docs/v1.5-release-runbook.md”。
-替换每个原始的“docs/<file>”。带有一个链接到“docs/en/<file>”的兼容性存根。‘ docs/zh-CN/<file>.md ’。

-[] **第一步：复制没有内联中文摘要的英文文档**

使用上次提交的“主要”版本的预摘要英语内容作为规范的英语来源。从规范的英文文件中删除之前的‘中文中文’块；英语散文必须保持只限英语。

-[] **第二步：完整翻译每个文档**

将标题、散文、表格、警告和列表描述翻译成技术简体中文。准确地保留代码栏、命令、API路径、环境变量、标识符、数值和url。将项目术语表用于重复出现的术语，如检索概要文件、摄取、质量门和知识库。

-[] **步骤3：添加交换机链路并保存相关资产**

在每个H1后立即添加语言开关。将每种语言的内部链接重写为相同语言的规范目录，同时保持指向外部url的链接不变。

-[] **步骤4：编写兼容性存根**

每一个旧的' docs/<file>。md '只包含一个不赞成的句子和到中英文规范文件的链接；它不得保留完整身体的第二份副本。

任务4：迁移和翻译内部设计/规范文档

* *文件:* *
-在‘ docs/en/superpowers/specs/ ’和‘ docs/zh-CN/superpowers/specs/ ’下为每个现有的‘ docs/superpowers/specs/* ’创建镜像文件，包括2026-07-20双语设计。
-替换原来的“docs/superpowers/specs/*”。带有兼容性存根的Md。

-[] **步骤1：恢复规范的英文规格**

从英文副本中删除内联中文摘要，并保留所有技术要求、验收标准和代码片段。

-[] **第二步：生产完整的中文规格**

翻译每个标题、段落、表格和需求，同时保持标识符和文字值不变。保持section顺序相同，使英文和中文文件在结构上保持可比性。

-[] **第三步：添加语言开关，修复内部链接**

确保每个规范都指向相同语言的运行手册、计划和README引用（如果存在对应的内容）。

任务5：迁移和转换内部实现计划

* *文件:* *
-创建镜像文件在‘ docs/en/superpowers/plans/ ’和‘ docs/zh-CN/superpowers/plans/ ’为每个现有的‘ docs/superpowers/plans/*.md ’
-替换每个原始的“docs/superpowers/plans/*”。带有兼容性存根的Md。

-[] **步骤1：将英文方案复制为规范技术文本**

保留复选框状态、命令、文件路径、测试名称和代码示例；从规范的英文副本中删除内联中文摘要。

-[] **第二步：翻译完整的计划散文**

将目标、架构说明、任务名称、解释和验证文本翻译成中文，同时保留复选框语法和文字命令。在不同语言之间保持任务编号和文件路径相同。

-[] **步骤3：添加开关和兼容性存根

添加相同语言的链接，并用指向两个规范对应项的存根替换遗留计划文件。

任务6：规范化链接并验证双语树

* *文件:* *
-创建：‘ scripts/docs/validate- bilingu- docs.ps1 ’
-修改：所有规范的Markdown文件和兼容性存根根据验证输出的要求。

-[] **步骤1：实现结构验证**

验证器必须断言：每个包含的源代码都有一个对应的‘ docs/en ’和‘ docs/zh-CN ’；每个规范文件都有一个语言开关；没有规范的散文文件包含代码/标识符之外的其他语言；并且没有分阶段进行基准测试。

-[] **第二步：执行链路验证**

解析Markdown链接，解析每个文件目录中的存储库相对路径，忽略‘ http:// ’， ‘ https:// ’， ‘ mailto: ’，锚和代码块，并且在缺少本地目标时失败。

-[] **步骤3：运行完整的验证套件**

运行powershell -NoProfile -File scripts/docs/validate-bilingual-docs。ps1 -Root。”、“git diff -检查”,和“rg - n  '^(<<<<<<<|=======|>>>>>>>)' 自述文件。md README.zh-CN。md文档”。预期结果：零验证错误、零空白错误和零冲突标记。

-[] **步骤4：验证基准不变性**

将迁移后的基准散列与Task 1基线进行比较，如果有任何基准散列不同，则失败。

任务7：审查、提交和准备推送

* *文件:* *
—Review：所有stage文件

-[] **步骤1：审查阶段范围**

运行‘ git diff——cached——stat ’，确认只有README/docs、迁移脚本和翻译配置被更改，并且确认没有生成工件或基准文件被暂存。

-[] **步骤2：执行存储库检查**

运行文档验证器，‘ git diff——cached——check ’，如果可用，检查存储库的现有文档/测试。记录准确的通过/失败输出。

-[] **第三步：按照存储库约定提交

使用‘ docs：将文档拆分为en和zh-CN树’，其主体注明规范英语、完整中文副本、兼容性存根和基准保存。

-[] **第四步：验证后再推送**

将已验证的提交推送到请求的远程分支；如果“主”前进，不要强行推动和停止。

##验证总结

-舱单计数匹配标准中英文对的数量。
-每个包含的Markdown都有两个规范语言文件，加上一个遗留存根。
—没有基准测试文件更改。
-解析所有本地Markdown链接。
—存在语言切换链接，并指向对应的语言。
- ‘ git diff——check ’和冲突标记扫描通过。
