# 组播播放架构与验证

## 播放与回退

`IjkVideoView` 管理一个前台播放器、Surface 和请求代次。用户选择的内核偏好与当前实际内核分别保存，旧频道回调不会更新新频道状态。音轨信息未知时保持为空，不推测为 MP2。

| 内核 | 视频解码 | MP2 音频 |
| --- | --- | --- |
| UTVPlayer | MediaCodec 硬件候选优先，初始化失败尝试下一候选；运行失败可转 Ijk 视频软解 | 真实 `audio/mpeg-L2` 硬件能力优先；无硬件或硬件失败时使用 Ijk/FFmpeg 音频 renderer |
| IjkMediaPlayer | 按硬解开关配置 MediaCodec；全局关闭时 HEVC 也关闭 | 使用内置 FFmpeg MP2 软解 |
| AndroidMediaPlayer | 由系统选择，无法保证必定硬解 | 已知 MP2 且无硬件时直接选 Ijk；不支持音频通知或确认的 MP2 格式错误触发同地址 Ijk 回退 |

UTV 的 MP2 硬件失败会禁用当前请求的系统音频 renderer，重新选择 MP2 软解 renderer。保持同一 ExoPlayer、MediaSource 和前台 Surface；Exo 在错误恢复中仍可能重建内部解码器。下个请求重新启用系统音频 renderer，因此 AAC 等音频不受影响。设备解码器失败缓存保留 5 分钟。原生 `libijkmp2jni.so` / `libijkffmpeg.so` 优先用于 MP2；无 JNI 的 JVM 使用现有 Java 解码路径。

## 切台与传输

普通 UTV 切台保留 Surface，由 Exo 的 `canReuseCodec` 判断是否复用，遵守分辨率、输入缓冲上限、色彩与设备 workaround。移除强制 4K 配置与将拒绝结果改为可复用的逻辑。

`ProgressiveMediaSource` 持有 Exo 播放线程上的 MediaPeriod，不再由应用跨线程池化 MediaPeriod。新频道取消旧的原生接收并关闭 socket，避免等待 4 秒接收超时。HTTP 超时、连接失败与解码/格式错误分开处理，只有连接故障或首包空流触发组播回退。失败的外部 udpxy 按端点缓存 5 分钟；网络或代理设置变化时清除。HTTP 状态错误不会直接被视为网络不可达。

连续切台时，UTV 可预收方向上的一个邻台，接收器 3 秒后关闭。预收仅用于原生组播、本地代理直连或已缓存失败的外部代理路径；正常外部 udpxy 不额外建立预收连接。

缓存上限为 4 MiB，安全视频起点最多保留 1.5 秒。回放需有 CRC 有效、单 TS 包内完整的 PAT/PMT，且 H.264 的 SPS/PPS 或 HEVC 的 VPS/SPS/PPS 与 IDR 位于同一 PES，并带有效 PTS。回放从安全 PES 开始，丢弃视频 PTS 之前的音频 PES；缺表、非 IDR、丢包、不连续、溢出或过期时退回实时接收。HEVC CRA 暂不作为预收起点。RTP 校验 CSRC、扩展头、padding 与 TS 边界，过滤重复/迟到包，并处理序号回绕与 SSRC 变化。

Java MulticastSocket 在部分 Android 内核上使用 wildcard bind，不能可靠过滤同端口上的不同目的组地址。因此当前禁止同端口不同组播频道的并行预收。切入已预收的频道时，先保留其 socket，完成前台接管后才开始下一邻台预收。接管在 Exo 加载线程进行，最多等接收线程退出 250 ms；失败时关闭预收 socket，再打开实时接收。暂停和释放时清理未接管的预收资源。

## 起播指标

使用 `elapsedRealtime` 记录频道请求到首包、流信息、视频解码器就绪、prepared、首个视频帧与音频输出的耗时。HUD 的连接完成和延迟以首个视频帧为准。内部回退保留请求起点；缺少底层事件的内核将对应阶段记录为 `-1`，不填造数据。

## 本地验证

使用 Android SDK 34、Java 17 和 Gradle 8.5：

```bash
ANDROID_HOME=/path/to/android-sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

测试覆盖解码器排序/失败缓存/复用边界、MP2 参考 PCM、系统音频回退、旧请求回调隔离、TS/RTP 缓存有效性、预收 socket 接管/取消/过期、下一台调度时序、阻塞原生接收取消、HTTP 格式/状态错误不触发组播回退与既有启动 smoke tests。MP2 参考样本由 FFmpeg 生成（48 kHz 双声道、997 Hz 正弦、192 kbps、96 ms），PCM 相对 RMS 误差要求小于 0.005；这只验证该样本的 Java 解码路径，不代替 Android 原生库测试。

真机仍需验证目标电视盒子的 H.264/HEVC 和 MP2 硬件能力、原生 JNI 解码、首帧/声音耗时 P50/P95、连续快速切台及同端口直播行为。尚无真机性能测量，不宣称已达到某个起播或切台时间。
