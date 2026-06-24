# Skills 参考手册

> 生成日期: 2025-06-19 | 更新日期: 2026-06-22 | 总计: 101+ skills

---

## 一、GStack Skills（53 个）

GStack 是一套完整的产品开发工具链，覆盖从规划到部署的全流程。

### 规划类（Plan Mode）

| Skill | 描述 | 触发场景 |
**|-------|------|---------|
| `/plan-ceo-review` | CEO/创始人视角的全局规划审查。挑战前提、扩展范围、评估战略。4 种模式：范围扩展/选择性扩展/保持范围/范围缩减 | 战略决策、范围质疑、"要不要做大一点" |
| `/plan-eng-review` | 工程经理视角的架构审查。评估架构、错误处理、测试覆盖、性能、可观测性 | 技术方案审查、架构变更前 |
| `/plan-design-review` | 设计师视角的交互审查。评估信息架构、交互状态覆盖、用户旅程、AI slop | UI/UX 变更前 |
| `/plan-devex-review` | 开发者体验审查。评估 TTHW（上手时间）、环境搭建、代码可调试性 | 新成员入职体验优化 |
| `/plan-tune` | 自我调整提问偏好和开发者心理画像 | 减少重复提问 |

### 开发流程类

| Skill | 描述 | 触发场景 |**
|-------|------|---------|
| `/spec` | 5 阶段：模糊意图→精准可执行规格 | 需求模糊、需要明确方案 |
| `/office-hours` | YC 办公室时间：结构化问题定义+前提挑战+方案探索 | "我有个想法..."、"该怎么设计..." |
| `/autoplan` | 自动串联 CEO→设计→工程→DX 4 个审查，6 条决策原则自动判定 | 完整方案审查 |
| `/subagent-driven-development` | 用平行代理执行多独立任务 | 独立任务并行开发 |
| `/writing-plans` | 编写实施计划 | 多步骤任务开始前 |

### 代码质量类

| Skill | 描述 | 触发场景 |
|-------|------|---------|
| `/review` | Pre-landing PR 审查（正确性/复用/简化/效率） | 提交 PR 前 |
| `/adversarial-review` | 对抗审查：找 bug、安全漏洞、逻辑缺口 | 关键代码变更 |
| `/code-review` | PR diff 审查，支持 `--comment` 发评论和 `--fix` 自动修复 | 代码审查 |
| `/simplify` | 重构简化：DRY、复用、效率优化（不找 bug） | 代码清理 |
| `/investigate` | 系统性调试：根因分析→模式分析→假设验证→修复 | Bug、测试失败、异常行为 |
| `/systematic-debugging` | 4 阶段：根因调查→模式分析→假设→实施。禁止无根因的修复 | 任何技术问题 |
| `/test-driven-development` | TDD 工作流：先写失败测试→实现→通过→重构 | 新功能/修 bug 前 |
| `/verification-before-completion` | 声称完成前必须验证：实际运行、检查输出 | 完成工作前 |
| `/health` | 代码质量仪表盘 | 项目健康检查 |

### QA & 测试类

| Skill | 描述 | 触发场景 |
|-------|------|---------|
| `/qa` | 系统化 QA 测试 Web 应用，发现并修复 bug | 功能测试、回归测试 |
| `/qa-only` | 仅报告不修复的 QA 测试 | 仅评估、不修改代码 |
| `/browse` | 快速无头浏览器用于 QA 测试 | 页面验证 |
| `/canary` | 部署后金丝雀监控 | 发布后监控 |

### 设计类

| Skill | 描述 | 触发场景 |
|-------|------|---------|
| `/design-consultation` | 设计咨询：理解产品→研究格局→提出设计系统（审美/字体/颜色/布局/间距/动效） | 新设计项目 |
| `/design-review` | 设计师视角 QA：视觉不一致、间距问题、层级问题、AI slop 模式 | UI 打磨 |
| `/design-html` | 设计定稿：生成生产质量的 HTML/CSS | 设计交付 |
| `/design-shotgun` | 设计扫射：生成多个 AI 设计变体、对比看板、收集反馈、迭代 | 探索设计方向 |

### 部署 & 运维类

| Skill | 描述 | 触发场景 |
|-------|------|---------|
| `/ship` | 发布流程：检测+合并 base 分支→测试→审查 diff→更新 VERSION→更新 CHANGELOG→提交→推送→创建 PR | 发布代码 |
| `/land-and-deploy` | 着陆并部署工作流 | 部署到生产 |
| `/landing-report` | 只读队列仪表盘 | 查看发布状态 |
| `/setup-deploy` | 配置部署设置 | 首次配置部署 |

### 安全类

| Skill | 描述 | 触发场景 |
|-------|------|---------|
| `/cso` | 首席安全官模式 | 安全审查 |
| `/guard` | 全安全模式：危险命令警告+目录范围编辑 | 需要安全保障 |
| `/careful` | 安全护栏：危险命令的额外警示 | 高风险操作 |
| `/freeze` | 冻结：限制文件编辑到指定目录 | 保护特定目录 |
| `/unfreeze` | 解除 /freeze 限制 | 恢复全目录编辑 |
| `/security-review` | 安全审查 | 检查安全漏洞 |

### 上下文 & 会话类

| Skill | 描述 | 触发场景 |
|-------|------|---------|
| `/context-save` | 保存当前工作上下文 | 长期任务、需要换会话 |
| `/context-restore` | 恢复之前保存的工作上下文 | 继续之前的任务 |
| `/learn` | 管理项目知识库 | 记录经验教训 |
| `/retro` | 周度工程回顾 | 团队或个人回顾 |

### iOS 开发类

| Skill | 描述 | 触发场景 |
|-------|------|---------|
| `/ios-qa` | 真机 iOS QA | SwiftUI App 测试 |
| `/ios-fix` | 自动 iOS bug 修复器 | iOS bug |
| `/ios-design-review` | iOS App 真机视觉设计审计 | iOS UI 审查 |
| `/ios-clean` | 清理 DebugBridge SPM 包和 #if DEBUG 代码 | 发布前清理 |
| `/ios-sync` | 重新生成 iOS debug bridge | 更新模板 |

### 文档类

| Skill | 描述 | 触发场景 |
|-------|------|---------|
| `/document-generate` | 从零生成缺失文档 | 新模块/功能 |
| `/document-release` | 发布后文档更新 | 版本发布后 |
| `/make-pdf` | Markdown→高质量 PDF | 导出文档 |

### 工具 & 集成类

| Skill | 描述 | 触发场景 |
|-------|------|---------|
| `/codex` | OpenAI Codex CLI 封装：3 种模式 | 使用 Codex |
| `/benchmark` | 性能回归检测 | 性能监控 |
| `/benchmark-models` | 跨模型基准测试 | 模型评估 |
| `/setup-gbrain` | 配置 gbrain：安装 CLI、初始化本地 PGLite/Supabase brain、注册 MCP、信任策略 | 首次配置知识库 |
| `/sync-gbrain` | 同步代码到 gbrain、刷新 CLAUDE.md 搜索指引 | 代码变更后 |
| `/skillify` | 将成功执行的 /scrape 流程固化为磁盘上的永久 browser-skill | 自动化重复任务 |
| `/scrape` | 从网页提取数据 | 数据采集 |
| `/setup-browser-cookies` | 从真实浏览器导入 Cookie 到无头浏览会话 | 需要登录态的抓取 |
| `/gstack-upgrade` | 升级 gstack 到最新版 | 版本更新 |
| `/connect-chrome` | 启动 GStack 浏览器（AI 控制的 Chromium） | 浏览器功能 |
| `/pair-agent` | 配对远程 AI 代理与浏览器 | 远程协作 |
| `/find-skills` | 帮助发现和安装 agent skills | "有没有 skill 可以做 X" |

### 前端/React/Vercel 类

| Skill | 描述 | 触发场景 |
|-------|------|---------|
| `/ui-ux-pro-max` | UI/UX 设计智能：67 种风格、96 个色板、57 个字体配对、25 种图表、13 个技术栈 | UI/UX 设计、构建 |
| `/frontend-design` | 创建有特色的生产级前端界面，避免通用 AI 美学 | Web 组件、页面 |
| `/deploy-to-vercel` | 部署应用到 Vercel | "部署我的应用" |
| `/vercel-cli-with-tokens` | 用 Access Token 部署和管理 Vercel 项目 | Vercel CLI |
| `/vercel-optimize` | Vercel 成本和性能优化 | 账单、慢路由 |
| `/vercel-react-best-practices` | React/Next.js 性能优化指南 | React 开发 |
| `/vercel-composition-patterns` | React 组合模式（含 React 19 API） | 组件设计 |
| `/vercel-react-native-skills` | React Native/Expo 最佳实践 | 移动端开发 |
| `/vercel-react-view-transitions` | React View Transition API 动画 | 页面过渡动画 |
| `/web-design-guidelines` | Web 界面指南合规审查 | UI 审查、可访问性 |

---

## 二、Superpowers Skills（14 个）

Superpowers 是 Claude Code 官方插件，提供严格的开发纪律。

| Skill | 描述 | 触发场景 |
|-------|------|---------|
| `/using-superpowers` | 元技能——如何使用 skill 系统。**每次对话开始时强制加载** | 新会话 |
| `/brainstorming` | **任何创意工作前必须使用**——探索用户意图、需求和设计，然后才实施 | 新功能、改行为 |
| `/writing-plans` | 多步骤任务的实施计划编写 | 有需求文档后 |
| `/executing-plans` | 在新会话中执行已编写的实施计划，含审查检查点 | 执行已批准的计划 |
| `/subagent-driven-development` | 当前会话中执行有独立任务的实施计划 | 并行执行多任务 |
| `/dispatching-parallel-agents` | 2 个以上无共享状态的独立任务 | 独立任务并行 |
| `/test-driven-development` | TDD：写测试→实现→通过→重构 | 任何功能/bug 修复 |
| `/systematic-debugging` | 系统性调试：4 阶段严格流程。铁律：无根因不修复 | 任何 bug、测试失败 |
| `/verification-before-completion` | 声称完成前必须运行验证、确认输出 | 声称"完成"前 |
| `/requesting-code-review` | 完成任务/重要功能/合并前请求审查 | 任务完成时 |
| `/receiving-code-review` | 收到审查反馈后，实施前验证。要求技术严谨，不盲目实现 | 收到 code review |
| `/finishing-a-development-branch` | 实施完成、测试通过后——结构化选项（merge/PR/cleanup） | 分支完成 |
| `/using-git-worktrees` | 确保隔离工作空间存在（git worktree 回退） | 新功能、需要隔离 |
| `/writing-skills` | 创建/编辑/验证 skills | 创建新 skill |

---

## 三、Figma Skills（8 个）

设计到代码的双向桥接。

| Skill | 描述 | 触发场景 |
|-------|------|---------|
| `/figma-use` | **调用 `use_figma` 前强制加载**——Figma Plugin API 使用指南 | 任何 Figma 写操作 |
| `/figma-create-new-file` | **调用 `create_new_file` 前强制加载**——创建新 Figma 文件 | 创建新设计/FigJam/Slides |
| `/figma-generate-design` | 将应用页面/视图/多区域布局转化为 Figma。从代码构建完整页面 | "写到 Figma"、"从代码创建" |
| `/figma-generate-library` | 从代码库构建/更新专业级 Figma 设计系统 | 创建设计系统、组件库 |
| `/figma-generate-diagram` | **调用 `generate_diagram` 前强制加载**——在 FigJam 创建流程图/架构图 | 画图/Mermaid |
| `/figma-code-connect` | 创建和维护 Figma Code Connect 映射文件 | 设计-代码映射 |
| `/figma-use-figjam` | FigJam 上下文中使用 use_figma | FigJam 操作 |
| `/figma-use-slides` | Slides 上下文中使用 use_figma | Slides 操作 |

---

## 四、Kotlin Agent Skills（5 个）

Kotlin 语言专业工具。

| Skill | 描述 | 触发场景 |
|-------|------|---------|
| `kotlin-backend-jpa-entity-mapping` | Spring Data JPA/Hibernate 的 Kotlin 持久化建模。实体设计、标识、唯一约束、关系、抓取计划 | JPA 实体、N+1 问题 |
| `kotlin-tooling-java-to-kotlin` | Java→Kotlin 转换（Spring/Lombok/Hibernate/Jackson/Dagger 等框架感知） | Java 文件转 Kotlin |
| `kotlin-tooling-agp9-migration` | KMP 项目迁移到 AGP 9.0+ | AGP 升级 |
| `kotlin-tooling-cocoapods-spm-migration` | KMP 项目从 CocoaPods 迁移到 SPM | 依赖管理迁移 |
| `kotlin-tooling-immutable-collections-0-5-x-migration` | kotlinx.collections.immutable 0.3/0.4→0.5 迁移 | API 重命名适配 |

---

## 五、PhpStorm Plugin Skills（3 个）

PHP 开发工具。

| Skill | 描述 | 触发场景 |
|-------|------|---------|
| `php-code-review` | 使用 PhpStorm Inspections 审查 PHP 代码 | PHP 文件审查 |
| `php-project-guide` | PHP 项目基础指引：环境搭建、项目结构、编码规范、测试、Composer | PHP 项目入门 |
| `upgrade-php` | PHP 升级助手：修复废弃特性、兼容性扫描 | PHP 版本升级 |

---

## 六、Ponytail Skills（6 个）

"懒惰高级开发"模式——强制最简方案。质疑需求、用标准库、删代码而非加代码。

| Skill | 描述 | 触发场景 |
|-------|------|---------|
| `/ponytail` | 强制最简可行方案。质疑需求是否存在（YAGNI）、标准库优先、原生平台优先、一行代码优先。支持 lite/full/ultra 强度 | "ponytail"、"lazy mode"、"最简方案" |
| `/ponytail-review` | 专注过度工程的代码审查。找可删除的东西：重造的标准库、不必要的依赖、投机性抽象、死代码 | "审查过度工程"、"哪些可以删" |
| `/ponytail-audit` | 全仓库过度工程审计。扫描整个代码库，给出可删除/简化/替换为 stdlib 的排序清单。一次性报告，不执行修改 | "审计代码库"、"找膨胀" |
| `/ponytail-debt` | 收割所有 `ponytail:` 注释生成技术债台账。让 ponytail 刻意的捷径和延后被追踪，不会变成"以后=永不" | "ponytail 债"、"列出快捷键" |
| `/ponytail-gain` | 显示 ponytail 的可衡量影响：更少代码、更低成本、更快速度。一次性展示，非持久模式 | "ponytail 收益"、"节省了什么" |
| `/ponytail-help` | 所有 ponytail 模式/skills/命令的速查卡。一次性展示 | "ponytail 帮助"、"怎么用 ponytail" |

---

## 七、Claude Code 内置 Skills（8 个）

Claude Code CLI 自带的基础功能。

| Skill | 描述 | 触发场景 |
|-------|------|---------|
| `/init` | 初始化新的 CLAUDE.md 文件 | 新项目 |
| `/review` | Pull Request 审查 | PR 审查 |
| `/security-review` | 安全审查 | 安全审计 |
| `/run` | 启动项目 app 验证变更 | 运行/截图 app |
| `/loop` | 定时循环执行命令 | 定期任务 |
| `/verify` | 验证代码变更是否按预期工作 | 确认修复 |
| `/simplify` | 审查变更代码的复用性/效率 | 代码清理 |
| `/code-review` | diff 审查：正确性 bug + 复用/简化/效率 | 代码审查 |

---

## 八、其他 Skills（6 个）

| Skill | 描述 | 触发场景 |
|-------|------|---------|
| `deep-research` | 深度研究：扇出搜索→获取源→对抗验证→合成引用报告 | 深度调研 |
| `update-config` | 配置 Claude Code settings.json（hooks/permissions/env vars） | 配置修改 |
| `keybindings-help` | 自定义键盘快捷键 | 改键位 |
| `fewer-permission-prompts` | 扫描转录减少权限提示 | 过多弹窗 |
| `claude-api` | Claude API/Anthropic SDK 参考（模型、定价、参数、缓存、迁移） | API 使用问题 |
| `code-review:code-review` | Code review a pull request | PR 审查 |

---

## Skill 使用建议

### 按场景快速查找

| 场景 | 推荐 Skill |
|------|-----------|
| 🐛 有 bug | `/investigate` 或 `/systematic-debugging` |
| 💡 有新想法 | `/brainstorming` → `/office-hours` |
| 🏗️ 开始开发 | `/brainstorming` → `/writing-plans` → `/plan-eng-review` |
| 📋 审查方案 | `/plan-ceo-review`（战略）/ `/plan-eng-review`（架构）/ `/plan-design-review`（UI） |
| 🚀 要发布 | `/ship` → `/land-and-deploy` |
| 🔍 代码审查 | `/review` 或 `/code-review` |
| ✂️ 过度工程/简化 | `/ponytail-review` 或 `/ponytail-audit` |
| 📉 技术债追踪 | `/ponytail-debt` |
| ✅ 开发完成 | `/verification-before-completion` → `/requesting-code-review` |
| 💾 中断/恢复 | `/context-save` → `/context-restore` |
| 🎨 设计工作 | `/design-consultation` → `/design-shotgun` → `/design-review` |
| 📄 文档 | `/document-generate` → `/make-pdf` |
| 🏃 要运行 app | `/run` |
| 🔐 安全检查 | `/cso` 或 `/guard` |
| 📊 项目健康 | `/health` |
