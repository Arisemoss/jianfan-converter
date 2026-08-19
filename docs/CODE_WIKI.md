# 文简书斋 · 电子书处理工具 — Code Wiki

本文档是 `jianfan-converter` 项目的结构化代码知识库，帮助开发者快速理解整体架构、各模块职责、关键类与函数、依赖关系以及运行方式。

> 源 README：[README.md](../README.md) · 前端主程序：[ebook-tool.html](../ebook-tool.html) · Android 壳入口：[MainActivity.java](../android/app/src/main/java/com/wenjian/shuzhai/MainActivity.java)

---

## 目录

1. [项目总览](#1-项目总览)
2. [整体架构](#2-整体架构)
3. [目录结构](#3-目录结构)
4. [核心模块职责](#4-核心模块职责)
   - 4.1 CDN 库加载器
   - 4.2 编码检测与读取
   - 4.3 核心纯函数（转换 / 净化 / 去除 / 统计）
   - 4.4 顶层状态与 DOM 引用
   - 4.5 处理管线
   - 4.6 查找替换
   - 4.7 文件下载与平台适配
   - 4.8 批量处理
   - 4.9 主题系统与设置持久化
   - 4.10 撤销/重做
   - 4.11 差异对比（Diff）
   - 4.12 Service Worker 离线缓存
   - 4.13 Android 原生桥接层
5. [关键类与函数速查表](#5-关键类与函数速查表)
6. [依赖关系](#6-依赖关系)
7. [运行方式](#7-运行方式)

---

## 1. 项目总览

**项目定位**：一个纯前端、零构建、单文件的电子书文本处理工具「文简书斋」，所有处理均在浏览器本地完成，文件不上传服务器。

**核心能力**：
- 简繁转换（双引擎：在线 OpenCC 优先，离线内置词典兜底，支持 6 种方向）
- 文本净化、自定义去除、查找替换、批量打包下载
- 撤销/重做、统计面板、三态主题、设置持久化
- 附带一个**零第三方依赖的手写 WebView 壳**，用于构建 Android APK

**技术栈**：原生 HTML / CSS / JavaScript（单文件零构建）+ Gradle/Android（壳）。

---

## 2. 整体架构

项目采用「**单文件纯前端应用 + Android 壳封装**」的双形态架构：

```
┌────────────────────────────────────────────────────────────┐
│                     浏览器 / PWA                            │
│  ebook-tool.html（单文件，含 CSS + JS + HTML）              │
│    ├── 模块：CDN加载 / 编码检测 / 数据处理管线 / 下载        │
│    ├── 依赖：opencc-js + JSZip（CDN，按需加载）             │
│    ├── 离线词典：dict/dict.js（懒加载）                     │
│    └── 离线缓存：sw.js（Service Worker）                   │
└────────────────────────────────────────────────────────────┘
                          ▲ 同一份 ebook-tool.html
                          │ 通过「原生桥接 AndroidBridge」
                          ▼
┌────────────────────────────────────────────────────────────┐
│               Android APK（WebView 壳）                    │
│  MainActivity.java  ── 加载 assets/ebook-tool.html          │
│    ├── AndroidBridge.saveText / saveBase64（原生保存）      │
│    ├── shareFile / openFile（分享 / 用其他应用打开）        │
│    └── isSystemDark / setTheme（系统主题桥接）              │
└────────────────────────────────────────────────────────────┘
```

**关键设计原则**：
- **同一份前端代码**同时服务浏览器与 Android App：Android 壳把 `ebook-tool.html` 复制到 `assets/`，通过 `window.AndroidBridge` 注入原生能力，前端按有无该对象做能力探测与降级。
- **优雅降级**贯穿全局：OpenCC 失败→内置词典；JSZip 失败→逐个下载；原生保存失败→系统分享→传统 blob 下载。
- 代码一旦加载即完成初始化（顶部的 `INIT` 段）。

---

## 3. 目录结构

```
/workspace
├── ebook-tool.html              # 前端主程序（单文件，约 3000 行，HTML+CSS+JS）
├── manifest.json                # PWA 配置
├── sw.js                        # Service Worker（离线缓存）
├── README.md                    # 项目说明
├── LICENSE                      # MIT 许可
├── dict/
│   └── dict.js                  # 离线简繁词典（OpenCC 提取，S2T/T2S 映射 + 繁体字表）
├── android/                     # Android WebView 壳
│   ├── settings.gradle / build.gradle / gradle.properties
│   ├── gradlew / gradle/        # Gradle wrapper
│   └── app/
│       ├── build.gradle         # 应用构建配置（包名 com.wenjian.shuzhai）
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── assets/
│           │   ├── ebook-tool.html      # 与根目录同一份前端（构建时同步）
│           │   └── dict/dict.js         # 离线词典副本
│           ├── java/com/wenjian/shuzhai/MainActivity.java  # WebView 壳入口
│           └── res/                     # 图标 / 字符串资源
├── .github/workflows/build-apk.yml  # CI：构建 AMK 工作流
└── .trae-html-share-packages/     # 归档的 html 打包文件（zip，非源码）
```

---

## 4. 核心模块职责

> 前端逻辑全部位于 [ebook-tool.html](../ebook-tool.html) 的 `<script>`（约从 1260 行起），按注释分块组织。

### 4.1 CDN 库加载器

`script` 标签后即 `CDN LIB LOADER` 区块。

| 函数 | 职责 |
|---|---|
| `loadScript(src, timeoutMs)` | 动态加载外部脚本，支持 8s 超时，返回加载是否成功的 Promise |
| `ensureOpenCC()` | 确保 OpenCC 可用；`typeof OpenCC` 已存在则直接返回，否则拉取 CDN 的 `full.js` |
| `ensureJSZip()` | 确保 JSZip 可用（仅批量打包时按需加载）；缓存 Promise 避免重复加载 |

**说明**：OpenCC 后台预加载（不阻塞首屏），完成后调用 `updateEngineBadge()` 升级引擎标识。

### 4.2 编码检测与读取

格式：智能识别文本文件编码，避免乱码。

- `SUPPORTED_ENCODINGS`：支持 UTF-8/16、GB 系列、BIG5、Shift_JIS、EUC-JP/KR、西文等。
- `detectBOM(uint8)`：检测 BOM 头。
- `detectChineseEncoding(uint8)`：基于双字节范围打分，区分 GBK/BIG5。
- `detectJapaneseEncoding(uint8)`：区分 Shift_JIS/EUC-JP。
- `detectEncoding(uint8)`：BOM → 中文 → 日文 → 默认 UTF-8。
- `tryDecode(uint8, encoding)`：用 `TextDecoder` 解码并按 `\uFFFD`（替换符）比例给置信度。
- `smartDecode(uint8)`：BOM → 自动检测 → UTF-8 → 回退链，返回置信度最高的结果。
- `readFileWithEncoding(file)`：读取文件并返回 `{ text, encoding, confidence }`。
- `extractEpubText(uint8)`：EPUB 特殊解析，用 JSZip 读 `container.xml`→OPF→按 spine 顺序提取 XHTML 纯文本。

### 4.3 核心纯函数（转换 / 净化 / 去除 / 统计）

这些是可测试的纯函数，不含 DOM/副作用。

| 函数 | 职责 |
|---|---|
| `CONV_DIRS` | 转换方向配置表（s2t/t2s/s2hk/t2hk/s2tw/t2tw） |
| `getConverter(direction)` | 生成/缓存 OpenCC Converter |
| `convertText(text, direction)` | 双引擎转换：OpenCC 失败回退 `builtinConvert` |
| `builtinConvert(text, direction)` | 内置字典逐字映射离线转换 |
| `cleanText(text, activeVals)` | 行级净化（去多余空格、统一标点、去特殊符、去链接/邮箱/零宽、Tab→空格） |
| `normalizePunctuation(text)` | 统一标点映射表 |
| `removeText(text, activeVals, customPatterns, useRegex)` | 去除 HTML/数字/英文/标点 + 自定义关键词/正则逐行删除 |
| `crossLineClean(text, activeVals)` | 跨行净化（去空行、合并多余空行） |
| `countTraditional(text)` | 繁体字统计：OpenCC 反向逐字对比优先，回退内置字表 |
| `countStats(text)` | 统计总字数/行数/段落/中文字/繁体/字母数字 |

工具辅助：`escapeRegExp`、`sanitizeFilename`（净化文件名）、`decodeFilename`、`looksLikeHash`（识别 hash 文件名）、`extractTitle`（提取标题）、`isCJKChar`、`escapeHtml`。

### 4.4 顶层状态与 DOM 引用

- `state` 全局对象：`{ rawText, processedText, fileName, fileSize, isProcessing, undoStack, redoStack, batchFiles }` —— 全应用唯一状态源。
- `$(id)`：`document.getElementById` 简写。
- `var xxx = $('yyy')` 批量 DOM 引用。
- `lastSavedUri` / `lastSavedMime`：Android 原生保存后的 URI，供「分享/打开」复用。
- 主题图标常量 `ICON_AUTO/LIGHT/DARK`（代理对写法避免 emoji 乱码）。

### 4.5 处理管线（PROCESSING PIPELINE）

- `processText()`：异步主入口，对 `state.rawText` 依次执行 **转换 → 净化 → 去除 → 跨行净化**，写回 `state.processedText`。大文件显示进度遮罩。
- `processChunks(text, chunkFn, onProgress)`：将文本按行分块执行 `chunkFn`，避免大文件卡死 UI；跨行正则最后统一处理。
- `getChunkLines(totalLines)`：动态分块大小，依据 `navigator.deviceMemory` 自适应（≤2GB 用 2000，否则 4000，总体 ≤ total/4）。
- `getCleanVals()` / `getRemoveVals()`：收集当前勾选的净化/去除项。
- 遮罩控制：`showOverlay` / `setOverlayProgress` / `hideOverlay`。

### 4.6 查找替换（FIND & REPLACE）

- `buildFindRegex(find, useRegex, caseSensitive)`：构造查找正则（支持原样/正则、是否区分大小写）。
- `findReplaceAll()`：对 `state.processedText` 全局替换，记录撤销，统计匹配数。
- `findCount()`：统计匹配数（处理后或原始文本）。

### 4.7 文件下载与平台适配（DOWNLOAD GUIDE）

- `downloadFile(data, filename, mime)`：**统一下载入口**，按环境选择最优路径：
  1. `window.showSaveFilePicker` → 系统保存对话框（桌面 Chrome/Edge）
  2. `window.AndroidBridge.saveText` / `saveBase64` → Android 原生保存到「下载」目录
  3. `navigator.share` → 系统分享（移动端）
  4. blob + `<a download>` → 传统下载（兜底），并延迟回收 objectURL
  - 保存成功后进入「分享 / 用其他应用打开」引导（`showDownloadGuide`）。
- `detectPlatform()`：按 UA 判断 ios/android/desktop。
- `PLATFORM_TIPS`：各平台保存位置提示文案。

### 4.8 批量处理

- `state.batchFiles`：批量文件列表（文件 + 状态 pending/run/ok/err）。
- `renderBatchList()`：渲染列表，动态按钮绑定。
- `btnBatchProcess` 点击处理：逐文件读取（`readFileTextSmart` 带编码检测）→ 分块执行管道 → 汇总。JSZip 可用则打包 `batch_processed_时间戳.zip` 一个文件下载，否则逐个下载。
- `readFileText(file)` / `readFileTextSmart(file)`：批量读取辅助。

### 4.9 主题系统与设置持久化

- `SETTINGS_KEY`：`wjshuzhai.settings.v1`（localStorage）。
- `loadSettings()` / `saveSettings()`：读写转换方向、勾选项、正则开关、自定义模式、主题。
- `systemPrefersDark()`：读取系统深色；Android App 优先用 `AndroidBridge.isSystemDark()`，WebView 环境下修正 matchMedia 不可靠问题。
- `applyTheme(mode)`：三态主题 auto/light/dark，更新 `data-theme`/`data-theme-mode`、meta 主题色、按钮图标，并通知原生导航栏（`AndroidBridge.setTheme`）。
- `watchSystemTheme()`：监听系统主题变化（原生 `__systemThemeChanged` 与 `matchMedia`）。
- `cycleTheme()`：循环切换 auto → light → dark → auto。
- `updateConvUI()` / `updateEngineBadge()`：转换开关与引擎标识 UI。

### 4.10 撤销/重做

状态快照 `pushUndo` 入栈（上限 50），`undo`/`redo` 在 `undoStack`/`redoStack` 间迁移 `{raw, processed}` 快照，`updateUndoRedo` 同步按钮可用态。快捷键：Ctrl+Z 撤销、Ctrl+Shift+Z 重做、Ctrl+Enter 处理（`keydown` 全局监听）。

### 4.11 差异对比（DIFF VIEW）

- `computeDiff(oldText, newText)`：O(n) 左右的简易行级 diff（上下文匹配 + 向前探测），返回 `{t: 'added'|'removed'|'context', text}` 序列。
- `renderDiff(oldText, newText)`：渲染对比结果，统计 +/- 行数与字数变化。

### 4.12 Service Worker 离线缓存

[sw.js](../sw.js)：`install` 预缓存 `./`、`ebook-tool.html`、`manifest.json`、`dict/dict.js`；`activate` 清理旧缓存；`fetch` 同源缓存优先，离线导航返回离线页面。在 INIT 段通过 `navigator.serviceWorker.register('sw.js')` 注册。

### 4.13 Android 原生桥接层

[MainActivity.java](../android/app/src/main/java/com/wenjian/shuzhai/MainActivity.java)：

- `onCreate`：配置 WebView（启用 JS/DOM/文件访问），设置 `WebViewClient`（拦截页面错误、恢复加载）与 `WebChromeClient.onShowFileChooser`（文件选择器，带 API 失败兜底），注入 `NativeBridge`，`loadUrl("file:///android_asset/ebook-tool.html")`。
- 内类 `NativeBridge`（暴露为 `AndroidBridge`）：
  - `saveText(text, filename)` / `saveBase64(base64DataUrl, filename, mime)` → `saveBytes`：Android 10+ 走 MediaStore 写系统「下载」目录并返回 content URI；API 24-28 写公共下载目录后注册媒体库。
  - `shareFile(uri, filename, mime)` / `openFile(...)`：通过 `ACTION_SEND` / `ACTION_VIEW` 携带 content URI 交给其他应用。
  - `isSystemDark()` / `setTheme(dark)`：系统主题桥接，同步导航栏颜色与图标（仅用 API 21+ 基础方法，避免旧设备类验证闪退）。
- 系统栏：`setupSystemBars` / `applyThemeBars`（状态栏深色、导航栏随主题）。
- `onActivityResult`：回调文件选择结果（必须回调否则 WebView 卡死）。
- `onBackPressed`：优先 WebView 返回栈。
- `onConfigurationChanged`：感知 uiMode，通知 JS 重应用主题。

---

## 5. 关键类与函数速查表

### 前端（ebook-tool.html）

| 类型 | 名称 | 说明 |
|---|---|---|
| 全局对象 | `state` | 唯一状态源 |
| 全局对象 | `window.AndroidBridge` | 原生桥接（仅 App 环境存在） |
| 纯函数 | `convertText` / `builtinConvert` / `cleanText` / `removeText` / `crossLineClean` / `countStats` | 数据处理核心 |
| 异步 | `processText` / `processChunks` | 处理管线 |
| 加载 | `ensureOpenCC` / `ensureJSZip` / `ensureBuiltinDict` | 依赖懒加载 |
| 解码 | `smartDecode` / `readFileWithEncoding` / `readFileTextSmart` | 编码识别 |
| 下载 | `downloadFile` | 平台自适应保存 |
| 批量 | `btnBatchProcess` 处理器 / `renderBatchList` | 批量打包 |
| 主题 | `applyTheme` / `watchSystemTheme` / `systemPrefersDark` | 三态主题 |
| 撤销 | `pushUndo` / `undo` / `redo` | 快照栈 |
| Diff | `computeDiff` / `renderDiff` | 行级对比 |
| SW | `sw.js` | 离线缓存 |

### Android（MainActivity.java）

| 类型 | 名称 | 说明 |
|---|---|---|
| Activity | `MainActivity` | WebView 壳入口 |
| 内部类 | `NativeBridge` | `@JavascriptInterface` 桥接集合 |
| 桥接 | `saveText` / `saveBase64` / `saveBytes` | 原生保存到下载目录 |
| 桥接 | `shareFile` / `openFile` | 系统分享 / 打开 |
| 桥接 | `isSystemDark` / `setTheme` | 主题同步 |

---

## 6. 依赖关系

### 6.1 前端运行时依赖

| 依赖 | 来源 | 用途 | 是否必需 |
|---|---|---|---|
| opencc-js | CDN（jsdelivr `full.js`） | 高质量简繁转换 | 可选（有离线兜底） |
| JSZip | CDN（jsdelivr） | 批量打包 ZIP、EPUB 解析 | 可选（有降级） |
| dict/dict.js | 本地静态 | 离线转换 + 繁体字表 | 离线功能必需 |
| 浏览器原生 API | `TextDecoder`, `MediaRecorder` 无关 | 编码解码、各类 File/Blob 处理 | 必需 |

**失败注入关系（优雅降级链）**：
```
OpenCC 加载失败 ──► builtinConvert（内置词典）
JSZip 加载失败 ──► 逐个文件下载
showSaveFilePicker 失败 ──► AndroidBridge ──► navigator.share ──► blob 下载
SmartDecode 低置信度 ──► 手动编码选择（encodingSelect）
```

### 6.2 Android 依赖

- **零第三方库**（`dependencies` 为空）；仅依赖 Android SDK（compileSdk 35 / targetSdk 35 / minSdk 24）。
- 前端 `assets/ebook-tool.html` + `assets/dict/dict.js` 是运行时资源，与根目录同步。
- 发布签名由 CI Secrets（`KEYSTORE_BASE64` 等）解码注入。

### 6.3 CI 工具链

[build-apk.yml](../.github/workflows/build-apk.yml)：JDK 17 + `gradle/actions/setup-gradle` → 解码 keystore → `./gradlew assembleDebug/assembleRelease` → 上传 debug/release APK artifact；带 tag 时自动发布 GitHub Release。

---

## 7. 运行方式

### 方式一：浏览器直接使用（无需构建 / 安装）

直接打开 `ebook-tool.html` 即可：
```bash
# 任意静态服务器（或直接双击打开）
python3 -m http.server 8000
# 浏览器访问 http://localhost:8000/ebook-tool.html
```
- 首次联网会加载 OpenCC / JSZip；离线时自动用内置引擎与逐个下载，功能不缺失。
- PWA：若访问根路径，`sw.js` 提供离线缓存，「安装到主屏」可独立运行。

### 方式二：构建 Android APK

**本地构建**（需 JDK 17 + Android SDK）：
```bash
cd android
chmod +x gradlew
./gradlew assembleDebug   # 生产 debug APK
# 或用 ./gradlew assembleRelease 生产 release（需要签名配置）
```
产物：`android/app/build/outputs/apk/debug/app-debug.apk`

**CI 构建**：推送代码触发 GitHub Action，在「Actions → Build APK」下载 artifact；打 tag 时自动发布 Release。

**APK 使用注意**：Android 壳加载 `assets/ebook-tool.html`，完全离线可用（内置词典），文件保存到系统「下载」目录，可一键分享/用其他应用打开。

### 环境要求
- 前端无 Node 依赖、无构建步骤；仅需现代浏览器（Chrome/Edge/Firefox/Android WebView）。
- Android 构建：JDK 17、Android SDK（Gradle wrapper 已内置）。