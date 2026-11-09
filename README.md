# 营销雷达 MarketingGuard

[![Build APK](https://github.com/qyliaowei/MarketingGuard/actions/workflows/build.yml/badge.svg)](https://github.com/qyliaowei/MarketingGuard/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/qyliaowei/MarketingGuard)](https://github.com/qyliaowei/MarketingGuard/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

> **直接下载 APK**：[Releases](https://github.com/qyliaowei/MarketingGuard/releases/latest) → `MarketingGuard-v1.2-debug.apk`（约 45 MB，debug 签名，需允许安装未知来源应用）

基于 Android 无障碍服务的**营销内容实时过滤/标记工具**。浏览任意 App（抖音、小红书、微信文章、短视频等）时，自动读取屏幕文本（必要时截屏 OCR），用《营销文案特征数据库》规则引擎记分，命中后在屏幕顶部弹出彩色警示条。

> 规则库：[`app/src/main/assets/marketing_filter_rules.json`](app/src/main/assets/marketing_filter_rules.json)
> 配套文档：[docs/营销文案特征数据库.md](docs/营销文案特征数据库.md)（特征库与谬误库）、[docs/规则更新工作流.md](docs/规则更新工作流.md)（规则如何持续进化）

## 功能

| 功能 | 说明 |
|---|---|
| 全局实时检测 | 无障碍服务监听窗口内容变化，递归抓取屏幕文本（标题/字幕/正文，限 220 节点、6000 字） |
| **两级检测** | 第一级：无障碍节点文本；第二级：节点文本不足时（纯画面视频/烧录字幕）截屏 → ML Kit 中文离线 OCR 补强（Android 11+，限流 3 秒一次） |
| 四级警报 | 🟢 正常 → 🟡 疑似（≥5 分）→ 🟠 高度可疑（≥10 分）→ 🔴 收网动作（命中"扣1/私信/直播间"等或 >15 分） |
| 屏幕警示条 | `TYPE_ACCESSIBILITY_OVERLAY` 实现，**无需申请悬浮窗权限**，6 秒自动消失，显示命中特征明细 |
| 防打扰 | 800ms 最小分析间隔；同一内容 15 秒内去重 |
| 反讽豁免 | 文本含"营销话术盘点/反诈/辟谣/逻辑谬误"等自我指涉词时降权 |
| **样本自动收集** | 命中 🟡 以上内容自动存入本地样本文件，主界面一键导出给 AI 分析 |
| **规则远程订阅** | 填订阅 URL 一键检查更新，规则存云端多设备共用；失败自动回退旧版本 |
| **三级规则来源** | 本地导入覆盖 > 远程订阅缓存 > 内置规则，主界面显示当前来源与版本 |
| **版本可视化** | 主界面显示规则版本号、更新日期、来源（内置/远程/本地） |
| 隐私安全 | 文本分析与 OCR 全部本地完成；网络权限仅用于下载规则 JSON；屏幕内容不保存、不上传 |
| 引擎自测 | 主界面一键用内置正/反样本验证规则引擎 |

> **规则更新闭环**：日常使用自动收集样本 → 导出给 AI 分析 → AI 扩充规则库 → 导入/订阅更新立即生效，持续对抗话术变身。详见 [docs/规则更新工作流.md](docs/规则更新工作流.md)。

## 项目结构

```
MarketingGuard/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # 无障碍服务声明
│   │   ├── assets/marketing_filter_rules.json   # 规则库（可直接替换/扩充）
│   │   ├── java/com/example/marketingguard/
│   │   │   ├── MarketingFilterEngine.kt     # 规则引擎：关键词+句式+收网+谬误记分
│   │   │   ├── RuleStore.kt                 # 三级规则来源：本地覆盖>远程缓存>内置
│   │   │   ├── RuleSync.kt                  # 远程订阅下载与校验
│   │   │   ├── OcrHelper.kt                 # ML Kit 中文离线 OCR
│   │   │   ├── MarketingAccessibilityService.kt  # 两级检测+警示条
│   │   │   └── MainActivity.kt              # 主界面：开关/订阅/导入导出/自测
│   │   └── res/ (xml 服务配置、布局、字符串、主题)
├── .github/workflows/build.yml          # GitHub Actions 自动出 APK
└── settings.gradle / build.gradle / gradle.properties
```

## 获取 APK（两种方式）

### 方式一：GitHub Actions 自动构建（推荐，无需本地环境）
1. 在 GitHub 新建仓库，把 `MarketingGuard/` 目录完整推送上去
2. 打开仓库 Actions 页 → 等待 "Build APK" 工作流完成
3. 在该次运行的 Artifacts 中下载 `MarketingGuard-debug`（即 app-debug.apk）

### 方式二：本地 Android Studio 构建
1. 打开 Android Studio → `Open` → 选择 `MarketingGuard` 目录
2. 等待 Gradle Sync 完成（需联网下载依赖）
3. 菜单 `Build → Build Bundle(s)/APK(s) → Build APK(s)`，产物在 `app/build/outputs/apk/debug/`

## 使用步骤

1. 安装 APK（需允许"安装未知来源应用"）
2. 打开「营销雷达」→ 点 **开启无障碍服务** → 系统设置中找到「营销雷达（内容过滤）」并打开，确认授权提示
3. 回到主界面确认状态显示「服务运行中 ✔」
4. 正常刷抖音/小红书/微信：检测到营销话术时，屏幕顶部自动出现彩色警示条，写明风险等级与命中特征
5. 纯画面/无字幕视频会自动走 OCR 补强（Android 11+，可在主界面关闭以省电）
6. 可随时用主界面开关关闭警示条（检测仍在后台进行），或点「测试引擎」验证

## 权限说明

- **无障碍服务权限**：用于读取当前屏幕文本做本地规则匹配。服务配置声明了 `canRetrieveWindowContent`，仅读取文本与描述，不拦截、不修改、不发送任何操作
- **截屏能力（`canTakeScreenshot`）**：仅在节点文本不足时用于 OCR 补强，识别在本地完成
- **不需要悬浮窗权限**：警示条使用 `TYPE_ACCESSIBILITY_OVERLAY` 窗口类型
- **网络权限（INTERNET）**：**只用于下载规则订阅 JSON**，不做任何内容上传；OCR 使用随 APK 打包的离线模型
- **不使用存储权限**：规则与样本都存在应用私有目录，导出样本走系统分享面板

## 规则更新（三种方式，优先级从高到低）

1. **远程订阅（最省事）**：主界面填写订阅地址 → 「检查更新」。适合规则放 GitHub 仓库/Gist，多设备共用一份
   - 示例：`https://raw.githubusercontent.com/你的账号/你的仓库/main/marketing_filter_rules.json`
   - 下载后自动校验，坏文件不落盘，失败保留旧规则
2. **本地导入**：新版 JSON 传到手机 → 「导入本地规则库」→ 选择文件 → 立即生效
3. **重新构建**：改 `app/src/main/assets/marketing_filter_rules.json` 后重新出包

> 优先级：**本地导入 > 远程订阅 > 内置规则**。随时可点「恢复内置规则」清空前两者。

规则结构：
- **加词**：往对应 `keywords` 类别的 `terms` 数组追加
- **加句式**：往 `patterns[].regexes` 追加正则（Kotlin `Regex` 语法）
- **加收网动作**：往 `cta_patterns.regexes` 追加（命中即红色警报）
- **调阈值**：改 `scoring.thresholds` 的 `yellow/orange/red`
- **版本管理**：更新后递增 `rules_version`（格式 `v1.x-YYYYMMDD`）并填 `updated_at`

## 已知限制与后续计划

- OCR 依赖 Android 11+ 的 `takeScreenshot`；低版本仅能做节点文本检测
- OCR 为整屏识别，会带上部分界面文字（噪声），已用 3 秒限流与去重控制
- 直播弹幕类高频刷新场景已做节流，但部分 App 可能限制无障碍读取
- iOS 版无法全局监控，后续可做"分享检测"形态（把文案分享进 App 分析）
- 后续可加：OCR 区域裁剪（只识别字幕区）、本地小模型语义打分替代纯规则

## 发布到 GitHub

仓库已在本地完成初始化（`main` 分支、首个提交、`.gitignore`、MIT LICENSE）。

### 方式一：一键脚本（推荐）

```bash
cd MarketingGuard
chmod +x scripts/push_to_github.sh
./scripts/push_to_github.sh MarketingGuard public   # 或 private
```

脚本会自动：检查/登录 `gh` → 提交改动 → 创建远程仓库 → 推送。

### 方式二：手动用 gh

```bash
cd MarketingGuard
gh auth login
gh repo create MarketingGuard --public --source=. --remote=origin --push
```

### 方式三：不用命令行（网页上传）

1. GitHub 新建空仓库（不要勾选 README）
2. 本地把 `MarketingGuard` 目录压缩，网页端「uploading an existing file」解压后的全部文件拖入
3. 提交即可

### 推送后建议

- 仓库 Settings → Topics：`android`、`kotlin`、`accessibility-service`、`mlkit`、`ocr`、`content-filter`
- Actions 页确认 `Build APK` 工作流通过，在 Artifacts 下载 `app-debug.apk`
- 若要启用规则订阅，把 `app/src/main/assets/marketing_filter_rules.json` 复制一份到仓库根目录（或单独建 rules 仓库），App 里填 raw 直链即可

### 注意

- **不要提交** `local.properties`、签名文件、`captured_samples.txt`（可能含个人浏览内容），`.gitignore` 已排除
- 项目使用 ML Kit，首次构建需联网下载依赖；APK 体积因内置中文 OCR 模型会偏大
