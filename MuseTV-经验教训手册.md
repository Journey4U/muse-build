# MuseTV 经验教训手册

记录 2026-10-19 至 2026-11-07 一路踩过的坑、根因、定版解法与规矩。**改代码、提交、构建、交付之前先查本册对应章节**；踩到新坑当场补记进本册（七分支同版同步）。配套文档：《MuseTV-保留版本备注.md》管版本与基线，本册管经验与禁区。

每条格式：**坑**（现象）→ 根因 → **定版/规矩**（以后怎么做）。

## 0. 总纪律（红线与口径）

- **红线 1**：main 上出现任何不是我方做的提交——先停下来，全树比对、审完内容，才许往上构建。无例外。（2026-10-22 教训：一批来历不明的 "Add" 同步提交替换了 build.gradle，在未审内容上叠建的 #74 当场阵亡。）
- **红线 2**：PCDN 是红线。保留/交付版本不许有 PCDN；彻查口径不只迅雷 thunder/xunlei，还含上游 FongMi P2P 模块（forcetech/tvbus/jianpian/mitv、libp2p/libp3p 等）的存在、启动时机、闲时联网；先报 Liu 定夺，不擅自删模块。
- **死规矩**：不许删/改 Liu 的任何 GitHub 仓库，尤其私有仓库 Journey4U/muse 绝对不能删；复刻只往新仓库写；fork/镜像仓库冻结保存，不追随上游自动更新。
- **基线规矩**：没特殊指定时，一切操作以「上一版验证通过的版本」为基线；每批新改动直接叠在上一批已验收版本上，不夹带、不跳版。「最新版」= 验收通过的版本，未出包也算（见版本备注）。
- **先复述后开工**：复杂改动先把规格复述给 Liu 审核拍板，再动手。复述多次拦下真实误解（本地源开关、壁纸显示、spider 规则），比返工便宜得多。
- **设备证据优先**：结论以 Liu 真机实测为准；他的设备证据推翻我的诊断时，立即改口并更正记录，不护短。根因解释必须立足代码结构、与设备无关——「这又不是专门给单台手机做的壳子」。
- **两次理论失败就埋点**：同一问题两版理论修复被实测推翻后，停止猜测，植入诊断日志让设备证据定位断点（重置 App 的 ResetTrace 先例）。
- **借鉴优先（公理原则）**：有前人认证的好方法/版本就直接用，不自己重走一遍再发明。修 bug/加功能先查参考壳（OK 5.1.6 大屏版、5.6.3、VesperTV、AVBox、webhtv、PeekPro、宝盒、影视仓），一个壳没有去别的壳找。
- **只动下令的线**：一线订单不授权相邻线；白名单伪装只留在 disguise 线。共享层改动先报备，并用手机构建复验。
- **做活要仔细**：反编译跳过的方法体必须翻 dex 字节码核实；共用规则立成独立方法，不内联进单处；核查到的疏漏记进审计/日志，不只记结论；尺寸不许写死（多处栽在写死上）；代码不要臃肿，死代码要清除不留置。

## 1. 构建与 CI

- **坑**：本地沙箱构建 Gradle 必死。根因：本沙箱实例子进程 loopback TCP 损坏（子 JVM 连父进程 127.0.0.1 表面成功、数据被吞），Gradle 守护进程/Worker/Kotlin 守护进程全靠 loopback 回连。**定版**：维持云构建（Liu 2026-11-02 定），不再挖本地构建，除非 Liu 重新下令。沙箱 `free` 显示的是宿主机内存总量，别拿它判构建内存。
- **坑**：云额度烧钱风险。**规矩**：GitHub Actions $0 预算，只用免费额度（每月 2000 分钟），用完暂停等恢复（每月 1 号重置），不加付款方式；改动攒批叠版，尽量一构建多带，不为已被覆盖的中间版单独构建。
- **坑**：dispatch 接口偶发 502/500。**规矩**：失败后先查 runs 列表确认没建成，再重试；run 编号会被吃掉跳号（如 #195），认构建以 head_sha 为准，不以编号连续性为准。
- **坑**：构建「成功」也可能是坏包。根因：云构建曾产出 classes.dex 损坏（D8 duplicate type_ids），装上才炸。**规矩**：每个交付包必须先跑 DEX 校验（0 重复）才许签名交付，见第 2 节。
- **监控纪律**：构建监控（每 5 分钟）只汇报、不写入/不 dispatch/不取消；构建期间每 5 分钟向 Liu 汇报进度（含无变化）；交付完成后停用监控。监控按「your_files 有无 *-dexfix.apk」算的「待交付队列」是假的（交付走下载链接），永远别拿它汇报。

## 2. 签名与交付管线

定版管线，一步不许省：下载 Release asset → DEX 校验 → aapt 核对（包名 com.muse.android.tv、versionName、targetSdk 28、ABI）→ `zipalign -p -f 4` → `apksigner sign`（`--v1-signing-enabled=true --v2-signing-enabled=true --v3-signing-enabled=true`）→ 验签证书 SHA-256 须为 `0ff9845a…`（CN=MuseTV-fix）→ 上传 → curl Range 验 HTTP 206 → 发链接。

- **坑**：app/build.gradle 里的 enableV1/V2/V3Signing 在 AGP 9 是 no-op。**规矩**：签名一律在交付环节用 apksigner 显式三签并本地验签，keystore = PKCS12 `muse.p12`（由 PEM 密钥/证书重建，PEM 才是根；密码只当次会话用，不落盘记录）。
- **坑**：apksigner verify 显示 v1=false 被当缺陷。根因：minSdk 24 的正常结果，与已验收管线一致。**规矩**：v2+v3=true 即合格，别为此返工。
- **坑**：VM 的 C locale 下 apksigner 把非 ASCII（全角括号）文件名搅成 `????` 报 FileNotFound。**规矩**：永远签 ASCII 临时文件，签完再改成中文交付名；上传副本的文件名就是链接末尾名，必须当版专属 ASCII 名（版本+线路+ABI），绝不复用 up64/up32 这类临时名。
- **坑**：apksigner 静默报 "java: not found"。根因：JAVA_HOME 与 PATH 必须分开两条 export，只设 JAVA_HOME 时 PATH 里没有 JDK。**规矩**：两条都设再签。
- **坑**：上传并行触发 429；上传被服务重启打断、结果未知。**规矩**：串行上传，一次一个；结果未知时等约 45 秒重传——重传安全，会生成新链接；别在未知状态下重复不可逆动作。
- 交付只用 muse.ai 文件链接（Liu 定，「用原来那种链接」），不用 SwissTransfer/GitHub Releases。链接约 48 小时过期；签名副本留盘，过期可重传重发。中间产物放 `~/workspace/tmp/`，不进 /tmp（会丢）。
- 汇报口径：以交付文件名/版本号打头，commit 哈希只作次要回执。

## 3. GitHub API 与提交纪律

- **路径坑**：读 ref 用 `GET /git/ref/heads/{branch}`（单数 ref），改 ref 用 `PATCH /git/refs/heads/{branch}`（复数 refs），建分支 `POST /git/refs`。写反必 404。
- **坑**：用命令行 `gh.py file` 取回的暂存文件首行带 `# sha=<blob>` 诊断标记，直接提交会让 javac 报「非法字符 #」、构建阵亡（#53 先例）。**规矩**：提交前剥掉首行标记；API 的 get_file 助手不带此标记，优先用它。
- **提交纪律**：一律走 commit_sync 流程——先断言分支 HEAD == 约定基线，全树递归 diff 必须恰好等于预期文件集（VERIFY OK）才算提交成功。删文件的提交用 sha=null 的 tree 条目。
- **坑**：PAT 速率被打爆（5000/小时，与监控共享）。根因：全树逐文件 GET 扫描。**规矩**：禁止逐文件全树扫描；用 tree+blob 批量取；控制请求量。
- **坑**：构建失败靠代码审计猜原因，猜错白烧额度。**规矩**：失败先拉 job 日志定因——日志接口会 302 跳 Azure 预签名地址，捕获 Location 后用普通 curl 下全文，再看错误行。
- **坑**：凭提交标题猜内容删仓库文件，把内容相同的文件误删，致资源链接失败（#75）。**规矩**：删任何仓库文件前逐个核内容；回退一律用前向提交。
- API 的 api() 不自动重试 5xx、拒绝重定向；blob/tree 内容寻址，重试安全；但 commit/dispatch 这类动作失败后必须先查实际状态再决定是否重做。

## 4. 分支与变体

- **七分支**：main（主线，手机+电视同分支）、m106-p2pfree（支线手机）、v41-p2pfree（支线电视）、mobile-v119-vesper-local / leanback-v2-vesper-local（vesper 手机/电视）、mobile-disguise / leanback-disguise（disguise 手机/电视）。versionName：主线/支线 1.0，vesper/disguise 1.1.0。
- **hook 接线坑**：`hook/` 模块只在 disguise 两分支是活依赖（SourceIdentity 的 SpoofManager 继承它）——那两分支 settings.gradle 须含 `include ':hook'`、app 须含 `implementation project(':hook')`、hook/build.gradle 插件声明须用 `apply plugin: 'com.android.library'` 旧式写法（catalog alias 与根 AGP 应用插件冲突）。其余五分支 hook 是死目录。清理/合代码时不许在这七分支上误接或误断。
- **同名文件多变体（2026-11-07 普查）**：同名同路径 ≠ 同内容。已知：LocalSourceHub 四条本地源线同版；手机 NovelReaderActivity 四线同版；电视 NovelActivity 两变体（A=main/v41 在线版、B=vesper/disguise 电视带本地模式+TTS 看门狗）；strings.xml 两变体（手机/电视分支）；LocalSourceSettingsDialog 两变体（mobile/leanback 源集）；App.java 三变体；VodConfig 两变体。**规矩**：跨线同步前逐分支取 blob 比对；整文件同步只用于已证同版的文件；变体文件按各自变体分别改、分别验（技能 cross_branch_variant_sync）。
- 产出规矩：手机只产 64 位（32 位能力保留为构建开关，不得写死删掉）；电视恒双 ABI splits（一个 Release 含 arm64+arm32 两个 asset，只交付被点名的那个）。
- targetSdk 封顶 28（有外部二进制要导入应用执行，29+ 禁止 exec）；compileSdk 37、minSdk 24。

## 5. 编译期 Java / Gradle 坑

- **坑**：字段初始化器引用自身或声明在后的字段——包括初始化器里的 lambda 函数体——javac 报 self-reference in initializer / illegal forward reference，云构建两次阵亡（v21 的 #199/#200）。**规矩**：写带 lambda 的字段前先排声明顺序；本地 javac 最小复现必须连真实文件的声明顺序一起复刻，否则复现结论无效。
- **坑**：Material Slider 的 `stepSize`/`valueFrom`/`valueTo` 写成 `app:` 命名空间，AAPT 在 processMobileReleaseResources 报 attribute not found（run #102 阵亡）。**规矩**：这三个是框架属性，用 `android:`；`app:tickVisible` 才留 `app:`。
- **坑**：buildSrc flatDir 的 jar 无传递依赖元数据，worker 运行时 NoClassDefFoundError。**规矩**：commons-compress 必须显式带 commons-io 这类运行时依赖，flatDir 里的 jar 依赖一律手动补齐。
- Chaquopy/Python 或反射按字符串调用的方法：类与方法必须真实存在且写 proguard keep，否则混淆后运行时找不到。
- 仓库里 `com/fongmi/android/tv/player/iso/IsoSessionManager.java` 是有意保留的 JNI 桥（见第 6 节），不许当改名残留删除。

## 6. MPV 与 media3 底座

- **坑**：从 v24 的 R8 混淆 dex 提取的 fongmi-media3-full.jar 里有坏类（PlayerSeekView 字段引用被混淆搅坏，指向自己）。**规矩**：混淆产物里的类不可靠；碰 Android 资源的 UI 类回 FongMi/media 源码取，并注意 app 模块里要 `import com.muse.android.tv.R;`。
- **坑**：混套——FongMi/media 分支 88 文件 Java + v24 的 9 个 .so 不是同一 build 出身，MPV 运行时初始化存疑。**定版**：MPV 权威 = webhtv，整套拿（42 文件 mpvplayer + is/xyz/mpv MPVLib + 双 ABI 共 20 个 .so 必须是同一 build 的匹配集），不与 FongMi/media 的 88 文件实现混用。webhtv 的目的仅限于解决 MuseTV 的 MPV 播放，其余增量（WebHome 生态等）不在范围。
- **JNI 桥**：webhtv 预编译 libplayer.so 硬编码类名 `com/fongmi/android/tv/player/iso/IsoSessionManager`——在原 fongmi 名处留无状态委托桥（静态方法转发 muse 实现 + proguard keep），muse 实现零改动。此桥永不删。
- **音频定版**（#55）：`ad` 全程不设、走 mpv 默认选码（原生软解）；软/硬解开关只控视频 hwdec。经实测：原生 aac、无 aac_mediacodec、无 Failed to flush。EXO 式自编 native 线不立项。
- 底座：media3 用 webhtv 的原版未阉割 AAR + 本地 maven，不用混淆/阉割版；15 个 AAR 曾被剥成纯资源包是弯路，别重走。
- **黑屏坑**：MPV 切应用回来黑屏。定版：`PlaybackActivity.onStop()` 对 MPV 引擎跳过 `detachPlayerView()`，EXO 不动（2026-11-05 验收）。

## 7. 小说阅读器与朗读（手机版）

- 翻页终态（#85–#89 已定版）：底栏 5 胶囊（上一页｜上下翻页｜平移翻页｜仿真翻页｜下一页）；flip_mode 0=上下/1=仿真/2=平移；按下不抓页、>4dp 方向锁定（左滑下一页/右滑上一页）、竖直主导不翻页、到头不抓页、双击呼出、单击无动作、**边缘起始手势全程不归阅读器**（兜底 24dp，那是系统返回/退出手势——动手势类改动必须主动自查与系统机制的冲突并写进方案）；提交 = 位移过 1/4 屏宽或甩动 ≥400dp/s 同向；重分页不打断手势；模式互切保位置。拖动 >4dp 藏框；开自动即藏、自然结束自动恢复、停自动只靠按钮。
- 「翻页后下一页第一行 = 上一页最后一行」是既定设计，不是 bug，除非 Liu 重新下令不再追修。
- **断句定版**（v19）：句末标点（\n。！？；…）满 20 字断；50 字无句末则回找软标点（，、：）断（切口距段首须 >10）；全无标点才 50 字硬切；切口后的收尾符号（」』""）】》…）并入本段。原 90 字硬切作废——强制切开本身就是错的，哪怕回退也有卡顿。
- **进度推进只认 utterance 的 onDone/onError 回调**。**永不用 `isSpeaking()` 判朗读进度**：v17 的 1 秒看门狗靠它推进，结果高亮冻死、声音被打断，「越改越差」被整批回退。不许用任何可打断声音的机制做跟随——Liu 定：朗读是靠耳朵听，声音连续（不跳段、不重段、不长停）第一优先，高亮与翻页只是辅助，永不许为辅助动字号行距或打断声音。
- **朗读中翻页自动跟读已撤销**（v21 实测无变化，Liu 2026-11-06 定不再修）：正路 = 暂停 → 翻页/拖动到目的页 → 再开朗读，`startTts()` 起播按当前视图锚定从目的页读（已实测无问题）。v17 看门狗、v21 待锚定两套机关均已撤除，不许再提议重做。
- **朗读锁翻页**（v20 起、v22 定版）：朗读会话中平移/仿真不可选（分页视图无跟随落点）；开读时若在平移/仿真，先自动切回上下滚动并自动隐藏工具框（双击照常呼出）。**暂停即解锁**（2026-11-06 改定）：暂停/读完/停止都解锁并恢复工具框；续读时重新锁定 + 切回滚动 + 按视图锚定。
- 字号/行距是活设置（改即重分页）：跟随/高亮几何必须读现行 Layout 按结构算，禁止只对某个字号用固定 fudge 量糊弄（2026-10-22 立规）。
- 已否决名单，未经 Liu 重新下令不许重提：#66/#67 的改法（保页/条漫缩放/条漫翻页按钮）、#70–#77 全部改法（已回退到 #69）。
- 打开阅读器/图片页时 finish 详情页（#62 的「保留详情页」已作废）。图片播放器：横翻/条漫双模式、跳页条、缩放、选话已交付；图片列数由屏宽+源图比例自适应（公式见源码 Product），只有特大档 size=3 恒单列。

## 8. 电视版小说阅读器

- leanback 阅读器是 `NovelActivity` 单 ScrollView+TextView，**全库无翻页模式**——手机版「朗读锁翻页」在电视上没有移植对象，不许再提议移植。
- **跨章续读锚定坑**：跨章自动续读进入的章节靠 `ttsContinue` 意图置位 `mTtsAnchorStart=false` 跳过首次锚定，但旧代码全程不复位——该章节里停读→翻页→再开读永远跳过锚定、回旧位置。**定版**（v12）：一次性消费，跳过一次后立即复位为 true。普通章节不受影响，排查同类标志位先查「谁复位」。
- 电视断句与手机定版同规则，两变体（A/B）同改同验。
- 电视版有 5 秒 TTS 初始化看门狗，仅 vesper/disguise 电视两分支有（2026-11-03 加），主线/支线电视刻意没有——别把「没有」误判为缺失去补，也别把手机的 isSpeaking 看门狗搬上电视。

## 9. 电视版（leanback）通则

- 改动只进 `app/src/leanback/**` + leanback 管线；共享层（src/main）原则上不动，要动先报 Liu 并用手机构建复验。
- **遥控器优先是第一原则**：每个交互行都要有遥控路径（方向键可达、OK 可操作）；设置项走「行 → 弹窗输入」的定式，不嵌遥控聚焦不了的裸输入框；交付前逐项对照手机版功能过一遍，不适配遥控的宁可放弃（技能 leanback_remote_operability 逐项查）。
- 主页页、分类页、筛选三条线 **100% 照 OK 5.1.6 大屏版**，不许自加东西（Liu 2026-10-25 定，此前「核心一致就行」的口径作废）。
- 导航定版模型（2026-10-25）：打开呈「首页页」，点首页按钮弹「源线路列表」；点任一分类进新页（类网站），新页首页按钮+分类项同置顶、无源/线路名称行；新页点首页 = 返回「主页页」；同线路切分类仍在新页、栏不动只换内容；长按首页 = 更新源配置。单集全屏不显示选集按钮、多集才显示；单集全屏隐藏上/下一集；胶囊严格借鉴 OK 5.1.6 原版。手机版对照：切线路整 rebuild；更新源配置 = logo 长按。
- 移植前置检查：清单须声明 WAKE_LOCK；移植前先 grep 同名 override； leanback `activity_file.xml` 焦点、长按 OK 跳转（folderMode）等已修点别回退。
- 电视详情进页即播；电视小说/漫画目录多列自适应（NovelCatalogDialog 同构选集次级页）。

## 10. 本地源（vesper / disguise 线）

- **spider 行定版**（2026-11-06，v23/v13 验收）：生成配置的 `"spider"` 行——①手动指定 jar 优先照旧；②未指定时探测 xbpq251118.jar（生成目录 jar/ 优先、其次扫描目录同名文件），探到才写默认行 `"spider": "./jar/xbpq251118.jar"`；③探不到整行不写。沿革（两个极端都错过）：最早「存空 = 不写」坑了点过清除的用户（永远缺 spider）；2026-10-27 改「探测不到也硬写默认」又坑了本地配置根本没有 json 源、文件夹里也没这个 jar 的用户（指向不存在文件的 spider 拖累配置加载）。实现单点 = `LocalSourceHub.getDefaultSpiderJar()` 只返回探测结果。YQ 类站点必须 per-site 写 `"jar": "./jar/xyqxbpq.jar"`，顶层 spider 盖不住。
- **路径绝不写死**：Liu 的配置路径与文件名都可变；一切路径从当前加载的配置 URL 通用推导，目录推导只认 URL 不认文件名。
- cfgo 不是 App 生成的：是配置旁的外部二进制（MissAV.py 旁，端口 12525，startCfProxyFromJava 拉起）——这正是 targetSdk 封顶 28 的原因。
- 本地源注入只对自家产品配置生效（`LocalSourceHub.inject()` 先判 `isAppProductConfig(configUrl)`）；相对 cfgo 路径以配置目录为基准解析。
- VesperTV 1.0.4 源码的两条 Liu 定制规则（MissAV 钉死生成 + PY 金牌规则）**不在该树的文本里**——日后更新 vesper 源码前，先把这两条规则从现行实现抽出，再往新树回贴，否则更新即丢失。
- 文件夹选择权限只走共享 PermissionUtil 的 WRITE_EXTERNAL_STORAGE，**绝不走 MANAGE_EXTERNAL_STORAGE**。
- 本地 logo/壁纸：生成时探测 jar 文件夹 gif>jpg>png>webp（webp 是 2026-11-05 加的），都没有就不写 logo/wallpaper 两行、壳子用自带默认；配置壁纸采纳时须 `Setting.putWall(0)`（CustomWallView 先看 wall 偏好、默认 1=内置，不置 0 配置壁纸不显示）。
- 本地小说：TXT（BOM→UTF-8→GBK 探测；章节正则 ≤42 字、≥3 章否则约 8000 字切块）、EPUB（二期，ZipFile 解析 TOC→NCX→标题回退，encryption.xml 存在即 DRM 明确报失败；MOBI/AZW3/PDF 不在范围）；书架导入只导文件夹本层、不递归；bookId = keepKey 或 `"local:"+path`；书签/进度按书存，不另造收藏/历史表。
- 白名单认壳只在 disguise 线：spider jar 在 Init.init(Context) 自查包名/应用名/签名 DN，不过约 5 秒 killProcess；过法 = SourceIdentity 伪装上下文。主力五分支已回退伪装，不许再往其他线带。

## 11. TTS 与电视语音环境

- **服务端无声变更坑**：微软 2026-10-10 把 Edge 大声朗读迁到新端点 `api.msedgeservices.com` 并启用 v2 版 Sec-MS-GEC 令牌，旧端点+旧令牌双重作废，表现为语音通道整体失效。语音类故障先怀疑服务端变更，别先翻 App 代码。定版链：TTS Server v1.25 官方包 + Edge v2 修复插件，电视默认引擎 = TTS Server。
- TTS Server「多语音」开着且无朗读规则时会弹「缺少阅读规则」且系统通道失败——单音色朗读须在 TTS Server 系统页关掉多语音。
- **TTS Server 不读安卓 speechRate/pitch**：MuseTV 内的朗读语速/音调对它无效，必须在 TTS Server 的 Edge 配置编辑页调；讯飞引擎下 App 内设置有效。别再为「语速设置没用」翻 App。
- 索尼电视 BRAVIA_4K_VH22：32 位、无「文字转语音」设置页、待机断局域网 ADB。改默认引擎的定版解法：装讯飞 32 位引擎 com.iflytek.speechcloud + 手机 Termux 先 `pkg upgrade` 对齐 absl 库（不对齐 adb 报 CANNOT LINK），再 `adb shell settings put secure tts_default_synth com.iflytek.speechcloud`（命令须带 adb shell 前缀；甲壳虫 ADB 助手写不了 secure 设置）。讯飞 64 位包是 arm64-only，电视装不上；32 位发音人名单写死 11 人、已封顶，别再找新发音人。电视 IP 是 DHCP 会变（.9 已作废、现 .227），连之前先确认。
- 沙箱出站代理打不通微软语音 WebSocket：语音合成无法在沙箱验证，一律以 Liu 设备实测为凭。

## 12. 设备与工作环境

- 配对设备通道只有消费级命令，**没有手机的 shell/logcat**；播放页 FLAG_SECURE 录不了屏。手机侧证据靠 Liu 的录屏、截图与 App 内日志页（http://127.0.0.1:9978/debug/logs，默认关）导出。
- 沙箱 Java 程序走 env 代理会失败（HttpURLConnection 隧道异常）：Gradle 发行包、SDK 组件用 curl 代下；exec 会话以 root 运行（user.home=/root），用预置 Gradle 缓存须显式 `GRADLE_USER_HOME=/home/hatch/.gradle`。
- 沙箱无本地语音识别（无 whisper/vosk/sherpa）：转录视频音轨此路不通，别再试。
- 后台 exec 轮询器可能被运行时重启杀掉：查构建状态以 GitHub API 为准，轮询器死了就按 head_sha 重起。
- Gmail 收件箱基本是本仓库的 Actions 通知；长期规则：删一次性/验证类与订阅/促销邮件，不做每日摘要。

## 13. 流程与沟通

- 构建由 Liu 下令才触发；触发后按第 1 节监控纪律走；Liu 不回复 = 通过，不追问验收、不自行推进基线。
- 汇报版本/基线以交付文件名打头；结论先行（Liu 问结论时先说结论）；问题先说清根因与方案、经确认再开工，不交「过渡版」。
- Liu 问「你认为呢」时直接给判断和理由，不把选择推回去。
- 改动范围：一处入口原则——复用已有入口，不给同一页面加第二条路径；新功能逐项与 Liu 研究决定，不自加。
- 本册与《保留版本备注》随每次定版同步更新：新坑、新定版、新否决项，先记日志（~/memory/ 每日记录），再择要进本册与技能。
