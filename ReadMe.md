# uTV — 北京联通 IPTV 播放器 (BjUnicomIptvPlayer)

**uTV** 是一款专为 **北京联通 IPTV** 打造的 Android / Android TV 高性能直播播放器应用。项目基于 **bilibili/ijkplayer** 播放器内核深度定制，并完整集成 **wuwentao/bj-unicom-iptv** 的节目源规范与巡检能力，支持 4K 超高清（HEVC 50fps）、高清及标清全频道流畅播放、秒级切台以及局域网组播地址动态扫描。

---

## 核心功能

### 1. 完整的北京联通 IPTV 频道库与分类管理
- **内置双版本节目源**：内置最新可用频道表（`bj-unicom-iptv.m3u`）及包含历史频道的 215 个全量频道表（`bj-unicom-iptv-all.m3u`），可在设置中随时切换。
- **多维频道分组**：支持 `全部频道`、`[高清]央视`、`[高清]卫视`、`[高清]北京`、`[高清]北京IPTV`、`4K超清`、`少儿`、`标清频道`、`动态扫描发现`、`收藏频道` 分类浏览。
- **快速检索与过滤**：支持按频道名称、台号、组播 IP 搜索，支持一键开启「仅显示可用频道」及频道收藏持久化。

### 2. 三种网络传输模式与智能无缝回退
- **路由器 `udpxy` 组播转单播（推荐）**：自动将 `rtp://239.3.1.x:port` 转换为 `http://<udpxy_ip:port>/rtp/239.3.1.x:port`（默认 `192.168.2.1:8012`）。
- **内置本地 `udpxy` 代理网关（`LocalMulticastProxyServer`）**：当路由器未安装 `udpxy`、或光猫 IPTV 口直连电视盒子时，应用在本地自动监听 `127.0.0.1` 代理端口，自动探测并绑定 IPTV 专网网卡（如无默认网关的 `eth0`），加入 IGMP 组播组并剥离 12 字节 RTP 协议头（含 CSRC 与扩展头），向播放器实时推送纯净的 MPEG-TS 流。
- **原生 RTP/UDP 组播直连模式**：直接通过本地组播引擎加入网卡 IGMP 组播组解封装播放。
- **智能故障回退**：当外部 `udpxy` 代理不可达时，自动无缝回退至内置本地组播网关并在当前会话中记忆状态，避免后续切台重复等待超时。

### 3. 深度定制的播放与音视频解码架构
- **流下载与解码器池化复用（`StreamDownloaderPool` + `CodecDecoderPool`）**：
  - 将流媒体网络接收、MPEG-TS 协议解析（`PAT` -> `PMT` -> `ES Header`）与音视频解码器解耦并池化管理，切台时异步回收与复用解码器实例，主线程零阻塞。
- **关键帧对齐与零慢放秒开**：
  - 针对直播流切入 GOP 中间导致首个视频 IDR 帧晚于音频帧、进而引发切台前几秒 `0.5x` 慢放的问题，下载器在首个视频 IDR 关键帧到达前自动过滤非关键帧视频包及早到的旧音频包，严格对齐 `PAT + PMT + 首个视频 IDR 关键帧 + 同 PTS 音频帧`，实现音画同步秒开。
- **全格式音频解码支持**：
  - **原生 MP2 音频支持**：内置多架构（`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`）定制版 `libijkffmpeg.so`，原生开启广电 IPTV 标配的 `MP2 (MPEG-1 Audio Layer II)` 解码器。
  - **杜比 AC-3 / E-AC-3 硬件伴音协同（`TsHardwareAudioCompanion`）**：针对 CCTV-4K、北京卫视 4K 等采用杜比 `AC-3` / `E-AC-3` 音轨的频道，通过零重复连接的 TS 旁路分发机制驱动 Android TV 硬件 `MediaCodec` + `AudioTrack`（含 HDMI 直通回退）同步输出伴音。
- **多播放内核与渲染切换**：支持 `IjkMediaPlayer`、`IjkExoMediaPlayer`、`AndroidMediaPlayer` 切换，支持硬解（MediaCodec H.264 / HEVC）开关、`SurfaceView` / `TextureView` 渲染视图切换，以及 6 种画面比例（适应屏幕、铺满裁剪、原始尺寸、拉伸全屏、16:9、4:3）调节。

### 4. 组播地址动态扫描与 `check_iptv_m3u.sh` 巡检引擎
内置可视化组播扫描与频道巡检工具（`ScannerActivity` / `MulticastScannerEngine`），提供三种运行模式：
- **巡检内置频道表**：逐频道探测连通性、实测延迟、视频编码/分辨率/帧率（如 `#1 hevc, 3840x2160,50fps`）及音频轨道参数（如 `#1 mp2,2ch,48khz`），自动比对频道名中的分辨率标识与实际分辨率是否一致。
- **组播网段动态扫描**：支持自定义网段（默认 `239.3.1.1 ~ 239.3.1.254`）及高频端口快速扫描 / 79 个联通已知端口全量扫描，自动发现新上线的组播频道并归入「动态扫描发现」分组。
- **原生执行 `check_iptv_m3u.sh` 脚本引擎**：在支持 Shell 工具链的环境中直接释放并运行增强版 `check_iptv_m3u.sh`。
- **标准化导出**：扫描完成后自动合并更新应用频道表，并支持一键导出带 UTF-8 BOM 的 `results.csv` 巡检报告与 `modified_iptv.m3u` 播放列表。

### 5. 电视遥控器与手机/平板触屏双交互适配
采用沉浸式 Material Design 3 暗色毛玻璃 HUD 设计，同时适配 Android TV 大屏与触屏设备：

| 交互方式 | 操作说明 |
| :--- | :--- |
| **电视遥控器** | • **上下方向键 / 频道加减键**：全屏状态下快速换台<br>• **0-9 数字键**：数字选台（右上角实时预览匹配频道名，按 `OK` 立即确认或超时自动跳转）<br>• **OK 键 / 左方向键**：呼出左侧频道抽屉并自动定位到当前播放频道<br>• **左右方向键（抽屉打开时）**：在频道分类之间快速切换<br>• **长按 OK 键 / 红键 (`PROG_RED`)**：收藏或取消收藏当前频道<br>• **绿键 (`PROG_GREEN`)**：打开「组播地址动态扫描与巡检」界面<br>• **黄键 (`PROG_YELLOW`)**：循环切换画面比例<br>• **菜单键 (`MENU`) / 蓝键 (`PROG_BLUE`)**：打开播放器与 `udpxy` 网络设置<br>• **返回键 (`BACK`)**：关闭抽屉/HUD，全屏状态下双击返回退出应用 |
| **手机 / 平板触屏** | • **单击屏幕**：显示/隐藏顶部与底部 OSD 控制栏，或收起频道抽屉<br>• **双击屏幕**：快速切换画面比例<br>• **屏幕左侧向右滑动**：呼出频道列表抽屉<br>• **向左滑动**：收起频道列表抽屉<br>• **全屏上下滑动**：快速切换上/下一频道<br>• **OSD 虚拟数字键盘**：触屏点击选台按钮即可呼出 0-9 虚拟数字键选台 |

---

## 项目结构

```text
app/src/main/
├── assets/
│   ├── bj-unicom-iptv.m3u          # 内置北京联通 IPTV 最新精简频道列表
│   ├── bj-unicom-iptv-all.m3u      # 内置北京联通 IPTV 215 个全量频道列表（含历史频道）
│   └── check_iptv_m3u.sh           # 增强版节目源巡检与组播网段动态扫描脚本
├── java/com/bjunicom/iptv/
│   ├── IptvApplication.java        # 应用入口：初始化 ijkplayer、持有 MulticastLock、启动本地组播代理
│   ├── config/
│   │   └── IptvSettings.java       # 播放模式、udpxy 地址、解码器与扫描参数持久化配置
│   ├── model/
│   │   └── IptvChannel.java        # 频道数据模型（含台号、分组、音视频轨道信息、连通状态）
│   ├── network/
│   │   ├── LocalMulticastProxyServer.java # Android 本地 IGMP 组播转 HTTP-TS 代理网关
│   │   └── StreamUrlResolver.java  # 组播 URL 与 udpxy / 本地代理地址解析转换器
│   ├── parser/
│   │   └── M3uParser.java          # M3U 播放列表解析与导出工具
│   ├── player/
│   │   ├── IjkVideoView.java       # 定制播放器核心视图与音视频轨道探测
│   │   ├── StreamDownloaderPool.java # 池化流媒体下载器（PAT/PMT 解析与 IDR 关键帧对齐）
│   │   ├── CodecDecoderPool.java   # IjkMediaPlayer 与硬件伴音解码器对象池
│   │   ├── TsHardwareAudioCompanion.java # Android TV 杜比 AC-3/E-AC-3 硬件音频伴音引擎
│   │   └── TsCodecDescriptor.java  # MPEG-TS 实时编解码器规格描述符
│   ├── repository/
│   │   └── ChannelRepository.java  # 频道仓库、分类过滤、搜索与扫描结果合并
│   ├── scanner/
│   │   ├── MulticastScannerEngine.java # 组播动态扫描与 check_iptv_m3u.sh 巡检引擎
│   │   └── ScanStatistics.java     # 巡检实时统计数据模型
│   └── ui/
│       ├── MainActivity.java       # 主播放界面（OSD、频道抽屉、遥控器与触屏交互）
│       └── ScannerActivity.java    # 组播动态扫描与节目源巡检界面
└── jniLibs/                        # 支持 MP2 解码的多架构 ijkplayer / FFmpeg 原生库
```

---

## 构建与安装

### 环境要求
- **JDK**：Java 8 或更高版本（推荐 JDK 17）
- **Android SDK**：`compileSdk 34`，`minSdk 21`（支持 Android 5.0 ~ Android 14 的电视、机顶盒、手机及平板）

### 编译命令

```bash
# 编译 Debug 安装包
./gradlew assembleDebug

# 编译 Release 签名安装包
./gradlew assembleRelease

# 运行单元测试
./gradlew testDebugUnitTest
```

生成的 APK 位于 `app/build/outputs/apk/` 目录下，可通过 `adb install` 安装至电视盒子或手机。

---

## 致谢 (Acknowledgments)

本项目的开发基于以下两个优秀的开源项目，特此向原作者及社区贡献者致以诚挚感谢：

1. **[bilibili/ijkplayer](https://github.com/bilibili/ijkplayer)**
   - 感谢 Bilibili 开源的基于 FFmpeg 的跨平台音视频播放器内核。`ijkplayer` 提供了扎实稳定的 Android 音视频解码、渲染框架与高度可扩展的 `IMediaDataSource` 接口，为本项目实现 MPEG-TS 直播流秒级切台、多架构硬解/软解协同以及 4K 超高清流畅播放奠定了核心基础。

2. **[wuwentao/bj-unicom-iptv](https://github.com/wuwentao/bj-unicom-iptv)**
   - 感谢 `@wuwentao` 整理并持续维护的北京联通 IPTV 组播频道列表（`bj-unicom-iptv.m3u`）、网络拓扑配置指南以及 `check_iptv_m3u.sh` 自动化节目源巡检脚本。本项目的内置频道数据源、分组规范、轨道探测格式以及组播巡检导出逻辑均源自该项目的宝贵成果。
