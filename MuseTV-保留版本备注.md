# MuseTV 保留版本备注

更新：2026-11-07。本文件两部分：**一、现行基线**（2026-10-27 起的新编号，四条线）；**二、历史保留集**（2026-10-26 Liu 定，永久保留，清理不得删）。七个分支同版同步；基线或保留集再变时，同步更新本文档。本文档旧版曾在各分支内容不一致、两分支缺失，2026-11-07 按 Liu 令统一更新并补齐。配套文档：《MuseTV-经验教训手册.md》管经验规矩，本文档管版本。

## 一、现行基线（新编号）

### 编号与命名规矩

- 四条线：main（主线）、side（支线）、vesper、disguise；各线从 v1 重排、独立编号，同一条线手机版与电视版同步编号；旧号（主线 v132/v53、支线 v42/m107、vesper v130 等）冻结，不再递增、不追改已交付文件。
- 交付文件名：`MuseTV-v<versionName>-<mobile|leanback>-v<N>-<线路>（摘要）[-arm32|-arm64]-dexfix.apk`；上传副本用当版专属 ASCII 名（如 `MuseTV-mobile-v23-disguise.apk`），绝不复用临时名。
- versionName 三段式：主线/支线 1.0（即 1.0.0），vesper/disguise 1.1.0；大结构→主版本、小功能→次版本、界面细微→修订；每次更新同步改 `app/build.gradle` 的 versionName，已交付基线不追改。
- 基线口径：构建成功 + 已交付 + Liu 验收通过才是基线；每批改动叠在上一批已验收版本上，不夹带、不跳版。某线验收后，其他线同版源码「同视为验收通过」，不出包、下次构建自然带出，编号不因此改动。
- 「最新版」口径（2026-11-06 Liu 立规）：验收通过就是最新版，哪怕那条线没出包。要「最新 X 发给我」而该版未出包时，先出包再发，不许拿上一个出过包的版本顶替。

### 当前基线表（2026-11-07）

| 线 | 手机基线 | 电视基线 | 分支（手机 / 电视） | versionName |
|---|---|---|---|---|
| main 主线 | v8-main | v8-main | main / main | 1.0 |
| side 支线 | v5-side | v8-side | m106-p2pfree / v41-p2pfree | 1.0 |
| vesper | v17-vesper | v22-vesper | mobile-v119-vesper-local / leanback-v2-vesper-local | 1.1.0 |
| disguise | v23-disguise | v13-disguise | mobile-disguise / leanback-disguise | 1.1.0 |

分支 HEAD（2026-11-07 记录，仅作回执；动工前以 GitHub API 实查为准）：

- main `da8d2e63`；m106-p2pfree `136dabb5`；v41-p2pfree `43265dd4`
- mobile-v119-vesper-local `9c8a7299`；leanback-v2-vesper-local `acac7bf7`
- mobile-disguise `80f63967`；leanback-disguise `53281534`

### 近期验收批次（2026-11-05 ~ 11-06）

- **EPUB 二期 + 壁纸显示开关修复**（2026-11-06 验收）：本地书架补 EPUB（有/无目录、DRM 明确报失败）；配置壁纸采纳时 `Setting.putWall(0)`。主线手机 v8-main 验收，全线源码同版。
- **重置 App 重启修复**（2026-11-06 关闭）：定版解法 = :phoenix 独立进程 RestartActivity 跳板 + 内置壁纸无感遮罩（弃闹钟方案，手机版自初版起从未成功过）。disguise 手机 v16 验收；各线手机源码同版、同视为验收通过。
- **手机小说朗读定版**（2026-11-06 验收）：分级断句（废 90 字硬切）+ 朗读锁翻页 + 暂停即解锁；朗读中翻页自动跟读已撤销（正路 = 暂停→翻页→再开读按视图锚定）。disguise 手机 v22 验收，四线手机同版。
- **电视小说朗读两修**（2026-11-06 验收）：断句换同款分级规则 + 跨章续读锚定改为一次性跳过。disguise 电视 v12 验收，四线电视同版（两变体）。
- **本地源 spider 按探测写**（2026-11-06 验收）：指定 jar 优先；未指定探到 xbpq251118.jar 才写默认行，探不到整行不写。disguise 手机 v23、电视 v13 验收，四条本地源线同版。

## 二、历史保留集（2026-10-26 Liu 定）

保留集：主线手机 v119、电视 v51；支线手机 m106 全屏版、电视 v41 OK 修复版。共 4 个版本、6 个 APK，与《MuseTV-基线说明.md》一致。保留集再变时，同步更新本文档。

### 主线基线（旧编号）

#### 手机 v119——删 P2P

- 文件：MuseTV-v1.0-mobile-v119（删P2P）-dexfix.apk（arm64，80278818 B，sha256 9f602ad3081d24b7cadaa5f895d75a90a0c4c28fd692776527742927afcecd2a）
- 提交：d86ca6d4（父 d439f3ec）
- 构建：手机 #123，Release mobile-build-123-1
- 交付：2026-10-26，验收：2026-10-26 18:50 通过
- 变化：删 P2P 全量（3 个 P2P AAR、Force/JianPian/TVBus 抽取器、forcetech/jianpian/tvbus 死目录，Source 去 3 注册，build.gradle fileTree 改显式只留 customactivityoncrash-2.4.0.aar，manifest 删 ed2k/magnet/jianpian 深链 filter 等，共 43 项 blob diff）；去 PCDN（迅雷 thunder 早已清）；含华为蜘蛛复制修复（Platform.getApplication() + proguard keep）；含全屏上一集/下一集入口、自动播放默认关；MANAGE_EXTERNAL_STORAGE、SYSTEM_ALERT_WINDOW 缺席。
- 地位：主线手机验证通过基线。

#### 电视 v51——OK 键修复

- 文件：MuseTV-v1.0-leanback-v51-arm64（OK键修复）-dexfix.apk（80315566 B，sha256 ed246d05e33a0bf20add95c984c6ed57da038d9eae671a6fea39c6b78b957c4c）；MuseTV-v1.0-leanback-v51-arm32（OK键修复）-dexfix.apk（69260534 B，sha256 a7fee51d0018ae5e16de501b8aa6d26721a31a3d3f83bff20cdb82b655cc34d5）
- 提交：95c6bc82（父 d86ca6d4）
- 构建：leanback #81，Release leanback-build-81-1
- 交付：2026-10-26，Liu 2026-10-26 20:29 定为主线基线（取代 v50）
- 变化：在 v50（删 P2P）基础上只修 OK 键 1 个文件（CustomViewPager.dispatchKeyEvent 照原版 super||g 顺序，先正常分发再走左右边缘翻页；此前 pager 页内按键全被吞，只有 pager 外的首页按钮有反馈）；去 P2P；去 PCDN；含华为蜘蛛复制修复；MANAGE_EXTERNAL_STORAGE、SYSTEM_ALERT_WINDOW 缺席。
- 地位：主线电视验证通过基线。

### 支线基线（旧编号）

#### 手机 m106——删 P2P + 全屏上一集/下一集（#125）

- 文件：MuseTV-v1.0-mobile-m106（删P2P+全屏上一集下一集）-dexfix.apk（arm64，80278818 B，sha256 7ecb2553077c65a985678c3c7cd1f15d761f6c11a84004cfbbfddd0a6936dce2）；上传副本名：MuseTV-mobile-m106-p2pfree-fullscreen-arm64.apk
- 提交：96decef0（m106-p2pfree 分支，父 c40cd5b4）
- 构建：手机 #125，Release mobile-build-125-1
- 交付：2026-10-26，Liu 2026-10-26 20:21 定为支线基线
- 变化：基于 m106（cat_js 解析支持）+ 去 P2P + 只加全屏上一集/下一集入口（复用 checkNext/checkPrev，多集显、单集藏，不加新方法/新串/新类）；去 PCDN；老基线自带的 MANAGE_EXTERNAL_STORAGE 仍在；华为 Platform.getApplication() 修法未带入本支线。
- 地位：支线手机基线。

#### 电视 v41——删 P2P + OK 键修复（#82，已验收通过）

- 文件：MuseTV-v1.0-leanback-v41-arm64（删P2P+OK键修复）-dexfix.apk（80315566 B，sha256 f3124092581ee76c39cd8c09fd672e3e0c2c2420c2a2542699aa50c0705960c1）；MuseTV-v1.0-leanback-v41-arm32（删P2P+OK键修复）-dexfix.apk（69260534 B，sha256 fa299fdd7d868c3a7c0def13b30857cc92502f6ad967a1d074a422687d722d5e）
- 提交：554d1dda（v41-p2pfree 分支，父 e2d5c73d）
- 构建：leanback #82，Release leanback-build-82-1
- 交付：2026-10-26，Liu 2026-10-26 20:10 确认 OK 键修复成功，20:21 定为支线基线
- 变化：基于 v41（首次无配置不卡转圈）+ 去 P2P + 修 OK 键（与主线 v51 同一修法）；去 PCDN；老基线自带的 MANAGE_EXTERNAL_STORAGE 仍在；华为 Platform.getApplication() 修法未带入本支线。
- 地位：支线电视基线。

### 保留集说明

以上 4 个版本为 2026-10-26 20:35 Liu 下令按清单清理后的保留集。已清理的版本：电视 v50、电视 v41（删 P2P，未修 OK 键）、电视 v41 原版（首次无配置不卡转圈）、手机 m106（删 P2P，未加全屏入口）、手机 m106 原版（v106 cat_js 解析支持）——本地文件已移入回收站（2026-11-25 前可恢复），GitHub Release+标签已删（leanback-build-79-1、leanback-build-80-1、mobile-build-124-1），Muse 链接已删 5 条。提交历史未动，原版 v41（991db9d6）、m106（ddf76b17）的树仍可从 Git 取回，仅作文件系统移植历史对照，与现行基线是两回事。

另：2026-10-26 16:35 Liu 纠正——电视 v41、手机 m106 也要保留，今后清理不得再删。Liu 的上传文件与源码备份一律保留，不得删除。
