# 番茄本地下载（fq-local-save）

番茄免费小说（`com.dragon.read`）的 **LSPosed 模块**：在阅读页宿主「下载」按钮旁边，
多出一个 **「⬇存本地」** 按钮，点一下就把**当前这本书连同插图**导出到本地：

```
/sdcard/Download/番茄本地/<书名>/
    <书名>.txt          ← 你要的纯文本（插图处留 ［插图］ 占位）
    <书名>.html         ← 图文混排，手机浏览器直接看（可关）
    插图/i0001.jpg …    ← 插图本体
    _导出报告.txt       ← 逐章字数校对，能看出有没有丢章丢块
    _运行日志.txt       ← 出问题先看这个
```

> **模块不实现任何解密算法**，也不碰密钥推导。
> 它跑在番茄进程内，直接调用**宿主自己的 native 解密例程**拿明文，然后落盘。
> 这是本项目能稳定工作的根本原因：番茄改算法、换密钥都不影响它。

---

## 一、安装

1. 手机需 root（KernelSU / Magisk / APatch 任一）+ LSPosed **v1.8+**
2. 安装 `out/fq-local-save.apk`
3. 打开 LSPosed → 模块 → 勾选 **番茄本地下载** → 作用域勾选 **番茄免费小说**（`com.dragon.read`）
4. **强行停止番茄**，再重新打开

Android 10 及以上：直接可用，不需要给任何存储权限（走 MediaStore）。
Android 8/9：需要宿主自己持有存储权限（模块是借宿主的权限写盘的）。

---

## 二、怎么用（两种方式，随便挑）

### 方式 A：全自动（推荐）

1. 番茄里打开一本书，翻到阅读页
2. 点右上角 **「⬇存本地」**
3. 面板里点 **「① 帮点宿主『下载』并等待抓取」**
4. 再点 **「③ 抓完自动保存」**
5. 然后就不用管了 —— 宿主把整本下载/解密完，模块自动导出到本地

> 如果第 3 步没反应（不同版本按钮位置不一样），**自己点一下右上角宿主的「下载」**也一样，
> 模块照样在旁边抓明文，第 4 步的自动保存会照常触发。

### 方式 B：手动（最稳，任何版本都能用）

1. 番茄里打开书 → 点阅读页右上角宿主的 **「下载」**（下载全书）
2. 等它下完（或者你直接读几章也行，读过的章节宿主同样会解密）
3. 点 **「⬇存本地」** → **「② 保存已捕获的 N 章到本地」**
4. 弹出结果，文件在 `/sdcard/Download/番茄本地/<书名>/`

### 按钮位置不对？

「⬇存本地」是**可以拖的**：
按住它拖到任意位置，松手即保存（下次还在那）。
想让它贴着宿主「下载」按钮，就拖过去；面板里还有
**「校准宿主『下载』按钮位置」**（拖红点到宿主下载按钮上再点一下红点），
校准完「① 帮点宿主下载」就能精确点到它。

### 按钮不想要了？

面板里点 **「隐藏悬浮按钮」**。想恢复：清掉番茄的应用数据会丢登录，
所以更推荐改 `SharedPreferences`（`/data/data/com.dragon.read/shared_prefs/fq_local_save.xml`
里的 `visible=true`），或者重装模块后首次进入即为显示状态。

---

## 三、章节顺序（v1.0.1 修的重点）

**v1.0.0 的锅**：章节序号只从章节名里抠「第N章」的 N，抠不到就是 `-1`，
所有 `-1` 被统一扔到末尾、再按标题字符串排 —— 而标题也是空的，
于是**退化成 `ConcurrentHashMap` 的哈希顺序，导出来完全乱序**。

**v1.0.1 改成四档策略，整本只用一个键**（`Store.order()`），按覆盖率自动挑：

| 档 | 排序键 | 什么时候用 | 权威性 |
|---|---|---|---|
| ① | **宿主自带章节序号** | `ChapterInfo` 的 `chapterIndex`/`index`/`chapterOrder`/`order`/`chapterNo`… 覆盖率 ≥ 90% | ✔ 权威 |
| ② | **章节名里的「第N章」** | 同上覆盖率 ≥ 90%（支持中文数字，`第一百二十三章`→123） | ✔ 权威 |
| ③ | **chapterId 数值** | 全部是纯数字，**且与抓取顺序单调率 ≥ 95%** | ✔ 权威 |
| ④ | **抓取顺序** | 以上都不成立时的保底（宿主下载/阅读本身按目录顺序走） | ⚠ 推定 |

番茄的目录模型长这样（反编译确认）：

```java
com.dragon.read.origin.rpc.model.ChapterList
    public int    index;        // ← 章节序号，最权威的排序键
    public String itemID;       // chapterId
    public String title;
    public String volumeID;
```

**③ 为什么要做单调率校验**：番茄有「章节调整」接口（`ChapterAdjustChapterList`），
`item_id` 未必随章节递增。所以先算它跟抓取顺序的单调率，≥95% 才敢用，
否则老实退回抓取顺序 —— 宁可保守，也不要自信地排错。

**面板和报告里都会写清楚用的是哪一档**：

```
章节顺序   抓取顺序  (宿主没给序号、章节名也没「第N章」；按捕获先后排)  ⚠ 推定
```

如果主人发现顺序还是不对，点面板里的
**「诊断：打印章节原始字段到日志」**，它会把每一章的
`chapterId / 章节名 / 名中N / 宿主index / 抓取seq` 写进 `_运行日志.txt`，
把那几行发我，我就能直接定位该用哪个字段。

---

## 四、原理（为什么能拿到明文）

番茄把章节解密收敛到一个 native 咽喉点：

```java
com.dragon.read.crypt.CryptManager
    public static native byte[] decrypt(String 密文, String 书级key, int mVer)
```

实现在宿主的 `libdragon_crypt.so` 里，**返回值恒为 gzip，解压即 XHTML**。

模块挂四个落点：

| 落点 | 作用 |
|---|---|
| `CryptManager.decrypt` (after) | 被动截获明文，最可靠，必然成功 |
| `ChapterInfo.<返回自身的方法>` (after) | 唯一的信息汇聚点：bookId / 书名 / 章节名 / chapterId / 密文 / keyVersion 一次拿全 |
| `DecryptKey.<init>` (after) | 学到书级密钥（v2 key + mVer），宿主机给什么就用什么 |
| `reader.utils.n.a` (after) | 覆盖「已下载、从 DB 读」的章节（这些不走 ChapterInfo） |

插图：番茄把章节插图以**明文 JPEG** 落在
`<filesDir>/<账号子目录>/prefix_public_<bookId>/image/<key>`，
`key` = 图片 URL 的 Java `String.hashCode()`。
模块跑在宿主进程内 → 直接 `File` 读它的私有目录，**不需要 root**。

---

## 五、版本适配（重点）

番茄每个大版本都可能挪包改名（`reader.*` → `kmp.reader.*`，混淆名换字母）。
模块用四层兜底，任何一层命中就继续工作：

1. **硬编码候选表**：每个目标类给 2~4 个已知类名（新旧包名都列上）
2. **按形状 hook，不认方法名**：`ChapterInfo` / `reader.utils` 上的方法**全部挂一遍**，
   再从「返回值 / this / 每个参数」里试着抽出 `bookId + content`；
   抽不出来就安静放过 —— 工厂方法改名、改签名都不影响
3. **字段名兜底**：`ChapterInfo` 的字段名对不上时，按「值长什么样」猜一次
   （密文 = 最长的 String 字段；`bookId` = 名字带 id 的短串）；
   `DecryptKey` 更直接：把所有长度 ≥ 8 的 String 字段 × 所有 int 字段**全部登记为候选密钥**，
   反正 `tryDecrypt` 用 gzip 魔数 `1f8b` 校验，错的密钥天然被淘汰
4. **DEX 字符串表扫描**（`Dex.java`，纯 Java，不依赖 DexKit）：
   直接读宿主 APK 里 `classes*.dex` 的 `string_ids`，按简单类名（`CryptManager` /
   `DecryptKey` / `ChapterInfo`）找候选，名字里带 `.reader` 的优先

任何一环失败都只记日志，绝不让宿主崩；`_运行日志.txt` 会写清楚哪个类没命中、
该类实际有哪些方法、字段是什么（方便下次直接改候选表）。

> 为什么不用 DexKit：它在 LSPosed 注入的进程里没人 `System.loadLibrary("dexkit")`，
> 会直接 `UnsatisfiedLinkError`。所以这里自己解析 DEX 头，零依赖。

---

## 六、排错

先看 `/sdcard/Download/番茄本地/_运行日志.txt`（面板里也能直接看尾部 200 行）。

| 现象 | 原因 / 处理 |
|---|---|
| 阅读页看不到「⬇存本地」 | ① 模块没在 LSPosed 里启用/勾作用域 → 重新勾选并**强停番茄**；② 日志里有 `DownloadUiState 未命中` → 会退化成「宿主前台常显」，稍等 1~2 秒；③ 按钮被拖到屏幕外了 → 清 `fq_local_save.xml` 里的 `btn_x/btn_y` |
| 点「保存」提示「还没有捕获到章节」 | 宿主还没解密过任何一章。先点宿主的「下载」，或先翻几章 |
| 只导出了几章，不是整本 | 模块只能拿到宿主**已解密**的章节。要整本，先让宿主把整本下载一遍（点「下载」） |
| **正文里出现两个标题** | 1.0.2 已修。若还有，点「诊断：打印章节原始字段到日志」，把某章的 `名=...` 和 txt 里那两行发我 |
| **导出后章节顺序不对** | 面板/报告里的「章节顺序」写着哪一档？`⚠推定` 说明宿主没给权威序号。点「诊断：打印章节原始字段到日志」，把 `[0001] id=... 名=... 宿主index=...` 那几行发出来即可定位 |
| 番茄重启后「已捕获」清零 | 缓冲在内存里，进程重启即清空。这是刻意的（不落盘中间态） |
| 导出成功但没看到插图 | 那几章本来就没图。`_导出报告.txt` 里的「插图」计数是判据 |
| 日志里 `CryptManager 未命中` | 宿主大改版了。把日志里 `DEX 候选=...` 那行发出来即可定位 |
| 导出目录里出现 `xxx (1).txt` | 正常情况不会。出现说明该 ROM 的 MediaStore 行为异常，删掉副本即可 |

---

## 七、工程结构

```
fq-local-save/
├── app/
│   ├── AndroidManifest.xml          # xposedmodule / scope 声明
│   ├── assets/xposed_init           # 入口类：com.fq.localsave.MainHook
│   ├── res/values/arrays.xml        # 推荐作用域
│   └── src/com/fq/localsave/
│       ├── MainHook.java            # 入口：Application.attach / Activity.onResume
│       ├── Hooks.java               # 四个抓取落点 + 下载回调调用 + 多版本类解析
│       ├── Store.java               # 内存状态：缓冲 / 密钥 / 阅读页存活信号 / 配置
│       ├── Dex.java                 # DEX 字符串表扫描（版本自适应兜底）
│       ├── Xhtml.java               # 解密后 XHTML → 正文块 + 插图定位
│       ├── Exporter.java            # 整本导出：TXT / HTML / 插图 / 报告
│       ├── Files.java               # 落盘：MediaStore 四级降级 + gzip/md5/文件名安全化
│       ├── Logs.java                # 日志：XposedBridge + 环形缓冲 + 节流落盘
│       └── Ui.java                  # 阅读页按钮注入 / 拖动定位 / 面板 / 进度
├── lib/api-82.jar                   # 编译期 Xposed API（不打进 APK）
├── tools/zipalign.py                # 纯 Python zipalign（本机没有 ARM64 版）
└── build.sh                         # 无 Gradle 构建
```

---

## 八、自己构建

```bash
bash build.sh
# 产物 out/fq-local-save.apk
```

链路：`aapt2 compile/link` → `javac` → `d8` → 打包 `classes.dex` →
`tools/zipalign.py` → `apksigner`（v2/v3 签名）。

`build.sh` 顶部四个变量按自己的环境改：`SDK`（Android SDK 根）、
`BT`（build-tools 版本目录）、`PLATFORM`（android.jar）、`KS`（签名 keystore）。

**两个本机坑（已写进脚本注释）**：

1. 本机 SDK 的 `aapt2 / zipalign` 是 **x86_64 ELF**，跑不了 →
   `aapt2` 改用 Termux 包里的 ARM64 版；`zipalign` 用 `tools/zipalign.py` 顶替。
2. `d8` / `apksigner` 本身是 shell 脚本（内部 `exec java`），跨架构可用，照用不误。
3. 必须 zipalign：Android 11+ 且 targetSdk ≥ 30 时，
   `resources.arsc` 不压缩且 4 字节对齐，否则安装直接失败。
   `zipalign.py` 会把所有条目重写成 STORED 并补齐对齐，脚本里自带自检输出。

---

## 九、已验证 / 未验证

| 项 | 状态 |
|---|---|
| 编译 + d8 + zipalign + v2/v3 签名 | ✅ 本机构建通过，`apksigner verify` 通过 |
| APK 结构（manifest meta-data / xposed_init / dex） | ✅ `aapt2 dump xmltree` 逐项核对通过 |
| 抓取落点类名 | ⚠ 来自公开项目的实测结论（fq-hook 已在 7.2.7.32 / 7.3.9.32 验证），**本机无番茄环境，未做真机联调** |
| 阅读页按钮注入 | ⚠ 原生锚点路径 + 悬浮拖动路径都实现了，但默认坐标需要真机微调（可拖） |
| 插图读取路径 | ⚠ 沿用公开实现（`prefix_public_<bookId>/image/<hash>`），未真机验证 |

**第一次上手建议**：装好后先在番茄里下载一本书 → 点「保存已捕获的 N 章」，
看 `_运行日志.txt` 里 `抓取路径=decrypt 截获` 有没有出现。
只要这行出现了，后面的导出就一定能成。

---

## 十、版本历史

| 版本 | 变更 |
|---|---|
| 1.0.2 | **修「正文里出现两个标题」**：番茄章节 XHTML 的第一个 `<blk>` 本身就是章标题，之前没去重。现在按归一化键（中文数字/阿拉伯数字、全角空格都算同一个）判定重复，合并成一行；首块比宿主章名更全时（带副标题）会升级成标题再吃掉它 |
| 1.0.1 | **修章节乱序**：排序改成四档策略（宿主序号 / 第N章 / chapterId单调校验 / 抓取顺序），面板与报告显示用了哪一档；新增「诊断：打印章节原始字段」；线程池改守护线程 |
| 1.0.0 | 首个版本：四点 hook 抓明文、阅读页按钮注入、整本 TXT + 插图导出 |

---

## 十一、免责声明

仅供**个人学习与已购内容备份**使用。请勿传播导出产物，请勿用于商业用途。
