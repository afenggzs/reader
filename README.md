# 小说阅读器 Lite（WebView 版）

一个纯本地、不联网的安卓小说阅读器。界面由**单个 HTML 文件**绘制，外壳只是系统 WebView。
壳本身是纯 Java，不依赖任何跨平台框架或脚本运行时，APK 内也没有原生库（全 ABI 通用）。

- 包名 `com.novelreader.webshell`，桌面名「小说阅读器 Lite」
- 当前版本 **1.1.0**（versionCode 29902516，2026-11-08 23:16）
- APK 约 400 KB
- 格式支持：TXT（UTF-8 / GBK 自动识别）、EPUB 2 / EPUB 3

---

## 目录结构

```
reader/
├── README.md                    本文件
├── native_shell/                安卓壳工程（可直接构建）
│   ├── gradlew / gradlew.bat    Gradle Wrapper（Gradle 9.3.1）
│   ├── gradle/wrapper/
│   ├── settings.gradle.kts
│   ├── build.gradle.kts         AGP 9.1.0
│   ├── gradle.properties
│   ├── local.properties         ← 指向本机 Android SDK，换机器要改
│   ├── version.properties       版本号（由 tool/stamp_version.py 生成）
│   └── app/
│       ├── build.gradle.kts     唯一依赖：androidx.webkit 1.15.0
│       ├── proguard-rules.pro
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── assets/index.html   ★ 整个界面就是这一个文件
│           ├── java/com/novelreader/webshell/MainActivity.java
│           └── res/               图标、主题、应用名
├── tool/
│   └── stamp_version.py         构建前刷版本号
└── dist/
    └── NovelReaderLite-1.1.0-20261108231600-epub.apk   当前版本的成品
```

**只有一份页面源码**：`native_shell/app/src/main/assets/index.html`。
它是自包含的单文件应用（HTML + CSS + JS 全在里面，无外部资源、无构建步骤），
直接改它、直接构建，不存在「改了一处忘了同步另一处」的问题。

---

## 构建

需要 JDK 17 + Android SDK（`compileSdk 36`）。先把 `native_shell/local.properties` 里的
`sdk.dir` 改成你机器上的路径。

```bash
# 1) 刷版本号：versionName 带时间戳，versionCode 取「距 1970 的分钟数」
python tool/stamp_version.py

# 2) 构建
cd native_shell
./gradlew assembleRelease        # Windows 用 gradlew.bat

# 产物
# native_shell/app/build/outputs/apk/release/app-release.apk
```

为什么每次都要刷版本号：`versionCode` 不变的话，系统会把新包当成同一版，某些 ROM 直接
拒绝安装，表现是「改了代码但装不上，或者装了还是旧的」。

`release` 目前用的是 **debug 签名**，构建出来直接能装、能覆盖升级。要正式分发，
需要在 `app/build.gradle.kts` 里换成自己的 keystore（`*.jks` / `*.keystore` 已在
`.gitignore` 里排除，不会被提交）。

---

## 版本库约定

`.gitignore` 把这四类挡在版本库外面：

| 范围 | 规则 | 原因 |
|---|---|---|
| 编译产物 | `build/` `.gradle/` `.kotlin/` `*.apk` `*.aab` `*.hprof` | 每次构建重新生成，1 MB 以上纯噪声 |
| 本机配置 | `local.properties` `.idea/` `*.iml` | `sdk.dir` 是绝对路径，各人机器不同 |
| **签名与密钥** | `*.jks` `*.keystore` `*.p12` `*.key` `keystore.properties` | 推上去等于连私钥一起送人 |
| 依赖缓存 | `__pycache__/` `.venv/` | 可再生 |

只有一处例外：`dist/*.apk` 用 `!` 反向放行，保留已发布版本的快照（400 KB，值这个体积，
方便回溯「这个 tag 对应哪个包」）。不想要连那行 `!dist/*.apk` 一起删掉。

另外 `gradle/wrapper/gradle-wrapper.jar` **不能**忽略 —— 少了它 clone 下来根本跑不了 `./gradlew`，
所以它和 `version.properties` 都在版本库里。

工作区根目录 `E:/workbuddy/works/reader/` 下还有一份更粗的 `.gitignore`，管的是这个目录之外的东西
（Flutter 工程残留的 859 MB 构建缓存、`备份/历史APK/` 里的旧包、探针临时目录）。两份不冲突：
更深层的规则优先，所以这里的 `!dist/*.apk` 依然有效。

---

## 页面怎么改

`index.html` 是普通网页，改完直接构建即可。想在桌面浏览器里调试，**必须走 http**：

```bash
cd native_shell/app/src/main/assets
python -m http.server 8080
# 打开 http://127.0.0.1:8080/index.html
```

用 `file://` 打开会看不到任何书：`file://` 是不透明源（opaque origin），浏览器会禁掉
IndexedDB，而书的正文就存在 IndexedDB 里。壳里之所以必须用 `WebViewAssetLoader`
也是同一个原因（见下文）。

---

## 阅读版式（这些数值是逐轮调出来的，改动前先想清楚）

| 项 | 值 | 说明 |
|---|---|---|
| 正文字号 | **19px** | `--rp-fs` 是唯一来源，`.reader-page` 与测量层 `.page-meas` 必须同用 |
| 正文左右边距 | **20px** | JS 里 `PAD_X = 20`，`pagePadFor()` 返回它；CSS 三处 `var(--rp-pad-x, 20px)` 兜底要一起改 |
| 一行字数 | **17 字**（360 屏） | 不是写死的：`fitLetterSpacing()` 实测选字距，20px 边距下 360 屏取 `-0.015em` 才排得下 17 字；393 以上屏宽仍是 `0.03em`，自然 18/19/20 字 |
| 章首页大标题 | 正文 **1.2 倍** | 字号、行高、与正文的空隙全走 `calc(var(--rp-fs) * 1.2)`，别写死像素 |
| 页脚 | 高 **24px** | 三栏：电池+时间 / 页码 `3/12` / 进度 `68.7%` |
| 进度精度 | 一位小数 | 落盘仍取整，只在显示时保留一位；≥99.95% 显示 `100%` |
| 正文起点 | 章名行 32px + 正文 padding-top 4px | |

分页是**字符级二分**：用测量层实测 `scrollHeight`，贪心取「最后一个还能放下的字」。
每页浪费不超过 0.88 行（物理极限），不会出现「底部空两三行只为了把一段推到下一页」。

配色（四套主题，全部在 `themes` 对象里）：

| 主题 | 纸底 | 正文/标题 | 辅助小字（章节名、页脚） |
|---|---|---|---|
| 日间纸（默认） | `#E8D5B0` | `#4B2503` | `#AF8C71` |
| 夜间黑 / 护眼绿 / 羊皮卷 | — | — | — |

日间纸的色值是专门定过的：`#4B2503` 在 `#E8D5B0` 上对比度足够，`#AF8C71` 只用于
12px 小字。**另外三套不要跟着套这两个色值**（`#4B2503` 在黑底上几乎看不见）。

---

## 壳的七个实现点（踩过的坑，别改回去）

**1. 必须用 `WebViewAssetLoader`，不能用 `file://`**
`file://` / `data:` 是不透明源，WebView 会禁掉 IndexedDB，书的正文就存不住，
表现是「导入的书下次打开空白」。它把 assets 映射成
`https://appassets.androidplatform.net/assets/`，有正常 origin。

**2. 申请了 INTERNET，但没有一行联网代码**
页面全部来自 assets、由 `shouldInterceptRequest` 本地拦截。留着这条权限是因为部分 ROM
的 WebView 在内核层先查权限，缺了直接 `ERR_ACCESS_DENIED`（打开一片空白）。
`usesCleartextTraffic="false"`，明文 http 一律走不通。

**3. `<input type="file">` 必须实现 `onShowFileChooser`**
WebView 默认不支持文件选择，不实现的话「导入」点了毫无反应，连回调都不给。

**4. 系统深色模式要关掉算法反色**
`WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)`。不加这句，系统一开
深色模式 WebView 会把整页反色，四套背景全废。不要用 `setForceDarkAllowed`，它不在公开 SDK 里。

**5. 返回键要分层**
网页是单页应用，状态都在自己的 JS 里，所以只能让它自己点自己的按钮。`BACK_JS` 依次尝试：
二级面板（`#subPanel.show`）→ 目录（`#toc.show`）→ 菜单（`#menu.show`）→
阅读页（`#reading.active` → 回书架）→ 都不是才返回 `'none'` 交给原生 `finish()`。

> 阅读页的判定**只能看 `#reading.active`**。三个 tab 页是常驻的（靠 `translateX` 错开），
> 阅读页只是以 `z-index:20` 压在上面，`#bookshelf` 的 `display` 永远是 `flex`。
> 早先按「书架不可见」判断，结果在正文里按返回直接退出应用。

Android 13+ 必须注册 `OnBackInvokedCallback`（`enableOnBackInvokedCallback="true"`），
因为 targetSdk 33+ 不再派发 `KEYCODE_BACK`；旧版本仍走 `onBackPressed`，两条路都进 `handleBack()`。

**6. 任何异常都不许直接崩**
`onCreate` 全程包 `try/catch`，出错就把异常栈 + 机型 + WebView 版本画在屏幕上；
主框架加载失败挂提示条；6 秒没 `onPageFinished` 也挂提示条。跑在别人手机上时，
截图比「打不开」三个字有用得多。调试可用 `chrome://inspect`（调试开关已开）。

**7. 系统栏只在纯阅读态隐藏**
书架 / 导入 / 我的 / 阅读页+菜单 / 阅读页+目录 → 显示状态栏；只有纯正文态隐藏。
由页面用 `MutationObserver` 盯 `#reading.active`、`#menu.show`、`#toc.show` 三个 class，
变化后调 `NativeBars.setImmersive(boolean)`。

- 不在各个开关里插钩子：菜单显隐有五六条路径，逐个必漏；
- 合并回调用 `setTimeout(fn, 0)`，**绝不能用 `requestAnimationFrame`** —— 页面在后台时 rAF 不跑，状态就永远推不出去；
- edge-to-edge 不变（透明系统栏 + `setDecorFitsSystemWindows(false)`），系统栏显隐只改可见性、不改 WebView 尺寸，所以开一次菜单不会触发重新分页；
- `--safe-top` 由原生用 `getInsetsIgnoringVisibility()` 实测后注入（老 WebView 的 `env(safe-area-inset-top)` 可能恒为 0）；
- 纸底是浅色，状态栏图标要转深色。

另外长按不能弹「复制/全选」：CSS `user-select:none`（input/textarea 例外）+ 原生
`setOnLongClickListener(v -> true)` 两层一起挡，否则书架上「长按删除」会先弹一排系统菜单。

---

## EPUB 解析

全部本地完成，**不引第三方库**（没有 epub.js，也没有 jszip）：

| 步骤 | 做法 |
|---|---|
| 解包 | 自己倒扫 EOCD → 中央目录 → 逐条取数据；`DecompressionStream('deflate-raw')` 优先，老 WebView 降级到内置的 inflate（puff 风格逐位解码） |
| 书目 | `META-INF/container.xml` → `.opf`，按 `localName` 取元素，绕开 `dc:` / `opf:` 前缀 |
| 分章 | **目录优先**：nav.xhtml（EPUB3）/ toc.ncx（EPUB2）的条目顺序说了算 |
| 正文 | 块级元素递归摊平成行，`<br>` 断行，`script`/`style` 丢弃 |

不引 epub.js 的原因：它是整套渲染引擎（iframe + CFI + CSS column 分栏），会把 19px 字号、
20px 边距、一行 17 字、24px 页脚、章首页标题比例、非循环翻页全部推翻重做，体积还要多 400 KB。
现在自研的解析一共 ~600 行，产物与 TXT **完全同构**：

```
{ text: string, toc: [{ title, start, end, level }] }
```

`start` / `end` 是**行号**（左闭右开，标题行不算正文）。正因如此，切片、分页、进度、
目录、翻页的代码一行都没为 EPUB 改过。

几个细节：

- **锚点失效兜底**：目录条目带 `#锚点` 就在文件内切多节；锚点 id 找不到（换个排版工具 id 就变了，
  很常见）就用目录标题在正文里匹配，连标题都对不上才不切 —— 宁可不切，也别按猜的位置切错；
- **粗目录保护**：条目数不到文件数一半（只列「卷一/卷二」那种）时不按目录合并位置，
  否则几十个文件会挤成一章、分页要算上千页；这时只借目录标题，仍按文件切；
- **标题去重**只剔「与本章标题同名」的那一行（`epubSameTitle` 认「第一章」=「第1章」），
  段落中间的 h2/h3 是正文分节标题，要留着；
- **嵌套目录**算出层级，目录面板里子条目缩进一档、字号小一档；
- 带 DRM 的报错，只混淆字体的（很常见）放行；
- 每 8 章 `await` 一次让出主线程，否则「正在解析 3/60」从头到尾不变，看起来像卡死。

### 插图

`<img src>` 和 EPUB3 常见的 `<svg><image xlink:href>` 都会从 zip 里取出来，转成 **data URL**
存进 IndexedDB 的 `img:<书id>`（与正文分开存，免得每次切片都多搬几 MB）。正文里只留一行占位
`\u0001F|<序号>|<宽>|<高>`。

三个必须记住的点：

- **宽高在分页时就固定死**。图片解码是异步的，等 `<img>` 加载完再撑开会把已经算好的页高推翻，
  页脚会被挤出屏幕。所以先按行宽等比缩放、按页高封顶，把 `width/height` 直接写进 HTML；
- **测量层用灰块，不加载真图**。`pageHtml(t, brief)` 在 `brief=true` 时输出同尺寸的
  `<div class="rp-fig-ph">`，二分测量零等待、绝对同步；显示层才输出真 `<img>`。
  两边尺寸来自同一个占位行，所以「量到的」就是「画出来的」；
- **图片在字符流里只占 1 个字符**（`\u0002`）。逐字二分的切点永远切不开它，一张图只会整块
  进某一页，绝不会被劈成两半。

单图超过 6 MB 直接跳过（否则会把 IndexedDB 和分页一起拖垮）。

---

## 已知限制

- **依赖系统 WebView 版本**。minSdk 24 的老机器上 WebView 可能只到 Chrome 60 左右，
  纸纹的 `mix-blend-mode` 之类可能不生效。建议 Android 9+。
- **翻页是 CSS transition**，不是 GPU 合成动画，低端机可能掉帧。
- **EPUB 侧重文字**：复杂的表格排版、脚注跳转不处理。纯漫画 EPUB 会提示「没有解析到正文」。
- **书库全部存在 WebView 的 IndexedDB 里**：卸载应用或清除应用数据就会连带清空，没有云端备份，导出功能也没有。换包名等于换一个空书架。
- **旧版导入的 EPUB 没有图片表**，需要重新导入一次才会显示插图（旧正文里本来就没有占位，不会出空白页）。
- **同一个包名的不同版本互相覆盖**，versionCode 涨上去之后想装回旧版要先卸载再装。

---

## 版本历史

| 版本 | 内容 |
|---|---|
| 1.1.0 | EPUB 插图；目录锚点失效时按标题兜底定位；版本号改为常量管理 |
| 1.0.0 | 仅 TXT；正文 19px / 边距 20px / 一行 17 字；页脚重做；标题 1.2 倍；配色定稿 `#4B2503` + `#AF8C71` |
