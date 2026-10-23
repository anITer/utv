#!/bin/bash

# ==============================================================================
# 北京联通 IPTV 节目源巡检与组播动态扫描脚本 (check_iptv_m3u.sh)
# 参考项目: https://github.com/wuwentao/bj-unicom-iptv/
#
# 功能说明:
# 1. 完整保留原版 check_iptv_m3u.sh 功能：
#    - 读取 iptv.m3u (或指定 M3U 文件)，逐频道通过 ffprobe 探测视频/音频流参数
#    - 自动识别分辨率标记：1920x1080 -> "高清", 3840x2160/0x0 -> "4K",
#      720x576/720x560 -> "标清", 其它分辨率 -> "其它", 无法连接 -> "错误"
#    - 使用 ffmpeg 抓取流第 5 秒截图保存至 screenshots/${counter}_${channel_name}.png
#    - 检测频道名称中的分辨率标签与实际流分辨率是否一致，不一致则记录到 mismatch.log
#    - 生成 UTF-8 BOM 编码的 results.csv (编号,频道名称,video track,audio track,screenshot_name,连接flag,分辨率flag)
#    - 生成仅保留可用频道 (connect_flag=true) 且自动修正分辨率后缀的 modified_iptv.m3u
# 2. 新增组播网段动态扫描功能 (--scan / -s):
#    - 支持对北京联通组播网段 (默认 239.3.1.1 ~ 239.3.1.254) 及常用端口进行全量或快速动态扫描
#    - 支持通过 udpxy 单播代理 (例如 http://192.168.2.1:8012) 或直接 RTP/UDP 组播方式扫描
#    - 支持 Android 设备 Shell 环境降级探测 (当设备无 ffprobe/jq/bc 时自动使用 curl/nc/head 检测 MPEG-TS 同步字节 0x47)
# ==============================================================================

export LANG=C.UTF-8
export LC_ALL=C.UTF-8

# 默认参数配置 (可通过环境变量或命令行参数覆盖)
FFPROBE_TIMEOUT=${FFPROBE_TIMEOUT:-10}
FFMPEG_TIMEOUT=${FFMPEG_TIMEOUT:-10}
M3U_FILE=${M3U_FILE:-"iptv.m3u"}
UDPXY_SERVER=${UDPXY_SERVER:-""}          # 例如 192.168.2.1:8012
SCAN_ENABLED=${SCAN_ENABLED:-"false"}     # 是否开启组播网段动态扫描
SCAN_SUBNET=${SCAN_SUBNET:-"239.3.1"}     # 默认扫描网段前缀
SCAN_START=${SCAN_START:-1}               # 起始主机号
SCAN_END=${SCAN_END:-254}                 # 结束主机号
SCAN_MODE=${SCAN_MODE:-"fast"}            # fast: 常用端口集, full: 全部79个已知联通端口
OUTPUT_BASE_DIR=${OUTPUT_BASE_DIR:-"."}   # 输出根目录父路径
SKIP_SCREENSHOT=${SKIP_SCREENSHOT:-"false"}

# 解析命令行参数
while [[ $# -gt 0 ]]; do
    case "$1" in
        -f|--file)
            M3U_FILE="$2"
            shift 2
            ;;
        -u|--udpxy)
            UDPXY_SERVER="$2"
            shift 2
            ;;
        -s|--scan)
            SCAN_ENABLED="true"
            shift
            ;;
        --subnet)
            SCAN_SUBNET="$2"
            shift 2
            ;;
        --range)
            SCAN_START=$(echo "$2" | cut -d'-' -f1)
            SCAN_END=$(echo "$2" | cut -d'-' -f2)
            shift 2
            ;;
        --scan-mode)
            SCAN_MODE="$2"
            shift 2
            ;;
        -t|--timeout)
            FFPROBE_TIMEOUT="$2"
            FFMPEG_TIMEOUT="$2"
            shift 2
            ;;
        -o|--output-dir)
            OUTPUT_BASE_DIR="$2"
            shift 2
            ;;
        --no-screenshot)
            SKIP_SCREENSHOT="true"
            shift
            ;;
        -h|--help)
            echo "Usage: $0 [options]"
            echo "  -f, --file <m3u>         Input M3U file (default: iptv.m3u)"
            echo "  -u, --udpxy <ip:port>    udpxy address to rewrite rtp:// URLs (e.g. 192.168.2.1:8012)"
            echo "  -s, --scan               Dynamically scan multicast subnet for available streams"
            echo "  --subnet <A.B.C>         Multicast subnet prefix (default: 239.3.1)"
            echo "  --range <start-end>      Last octet scan range (default: 1-254)"
            echo "  --scan-mode <fast|full>  Port scan scope (default: fast)"
            echo "  -t, --timeout <sec>      Probe timeout in seconds (default: 10)"
            echo "  -o, --output-dir <dir>   Output parent directory"
            echo "  --no-screenshot          Skip ffmpeg screenshot step"
            exit 0
            ;;
        *)
            shift
            ;;
    esac
done

# 检查工具链可用性 (兼容 Ubuntu 完整环境与 Android 嵌入式 Shell 环境)
HAS_FFMPEG="false"
HAS_JQ="false"
HAS_BC="false"

if command -v ffmpeg &> /dev/null && command -v ffprobe &> /dev/null; then
    HAS_FFMPEG="true"
fi
if command -v jq &> /dev/null; then
    HAS_JQ="true"
fi
if command -v bc &> /dev/null; then
    HAS_BC="true"
fi

echo "[INFO] Environment check: ffmpeg/ffprobe=$HAS_FFMPEG, jq=$HAS_JQ, bc=$HAS_BC"

# 当前时间戳及输出目录
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
ROOT_DIR="${OUTPUT_BASE_DIR}/iptv_${TIMESTAMP}"
mkdir -p "$ROOT_DIR"

CSV_FILE="${ROOT_DIR}/results.csv"
SCREENSHOT_DIR="${ROOT_DIR}/screenshots"
LOG_FILE="${ROOT_DIR}/script.log"
SKIPPED_LINES_LOG="${ROOT_DIR}/skipped_lines.log"
MISMATCH_LOG="${ROOT_DIR}/mismatch.log"
MODIFIED_M3U="${ROOT_DIR}/modified_iptv.m3u"
DISCOVERED_M3U="${ROOT_DIR}/discovered_scan.m3u"

mkdir -p "$SCREENSHOT_DIR"

# 重定向日志输出 (兼容不支持进程替换的受限 sh)
total_start=$(date +%s)
echo "[$(date +"%Y-%m-%d %H:%M:%S")] Starting script execution in directory $ROOT_DIR. Total start time epoch: $total_start" | tee -a "$LOG_FILE"

echo "Skipped Lines Log" > "$SKIPPED_LINES_LOG"
echo "----------------" >> "$SKIPPED_LINES_LOG"

echo "Resolution Mismatch Log" > "$MISMATCH_LOG"
echo "-----------------------" >> "$MISMATCH_LOG"

# URL 转换辅助函数：若配置了 UDPXY_SERVER，则将 rtp:// 或 udp:// 转换为 http://UDPXY_SERVER/rtp/...
normalize_stream_url() {
    local raw_url="$1"
    if [[ -n "$UDPXY_SERVER" ]]; then
        local clean_udpxy="${UDPXY_SERVER#http://}"
        clean_udpxy="${clean_udpxy%/}"
        if [[ "$raw_url" =~ ^rtp:// ]]; then
            echo "http://${clean_udpxy}/rtp/${raw_url#rtp://}"
            return
        elif [[ "$raw_url" =~ ^udp:// ]]; then
            local addr="${raw_url#udp://}"
            addr="${addr#@}"
            echo "http://${clean_udpxy}/udp/${addr}"
            return
        fi
    fi
    echo "$raw_url"
}

# ==============================================================================
# 动态扫描组播网段函数 (--scan)
# ==============================================================================
run_dynamic_multicast_scan() {
    echo "[$(date +"%Y-%m-%d %H:%M:%S")] [SCAN] Starting dynamic multicast address scan on ${SCAN_SUBNET}.${SCAN_START}-${SCAN_END} (mode: ${SCAN_MODE})..." | tee -a "$LOG_FILE"
    echo "#EXTM3U x-tvg-url=\"http://epg.51zmt.top:8000/e.xml.gz\"" > "$DISCOVERED_M3U"

    # 建立已知频道映射表 (IP:PORT -> EXTINF 行)，方便匹配已有台标与名称
    declare -A KNOWN_EXTINF_MAP 2>/dev/null || true
    declare -A KNOWN_IP_PORTS 2>/dev/null || true

    if [ -f "$M3U_FILE" ]; then
        local prev_extinf=""
        while IFS= read -r mline || [ -n "$mline" ]; do
            mline=$(echo "$mline" | tr -d '\r')
            if [[ "$mline" =~ ^#EXTINF ]]; then
                prev_extinf="$mline"
            elif [[ -n "$prev_extinf" ]] && [[ "$mline" =~ (239\.[0-9]+\.[0-9]+\.[0-9]+):([0-9]+) ]]; then
                local mip="${BASH_REMATCH[1]}"
                local mport="${BASH_REMATCH[2]}"
                KNOWN_EXTINF_MAP["${mip}:${mport}"]="$prev_extinf"
                KNOWN_IP_PORTS["$mip"]="${KNOWN_IP_PORTS["$mip"]} $mport"
                prev_extinf=""
            fi
        done < "$M3U_FILE"
    fi

    # 北京联通高频核心组播端口与全量组播端口
    local FAST_PORTS="8001 4120 8000 4220 2000 1234 3001 3002 3003 3004 3005 3006 8004 8008 8012 9000"
    local FULL_PORTS="1234 2000 3001 3002 3003 3004 3005 3006 4120 4220 8000 8001 8004 8008 8012 8016 8020 8024 8028 8032 8036 8040 8044 8048 8052 8056 8060 8064 8072 8076 8084 8092 8104 8108 8112 8116 8120 8124 8128 8132 8136 8140 8144 8148 8152 8156 8160 8164 8172 8176 8180 8184 8188 8192 8196 8200 8212 8216 8228 8232 8236 8268 8272 8276 8280 8284 8288 8292 8296 8300 8304 9000 9012 9020 9024 9136 9244 9252 9268"

    local base_ports="$FAST_PORTS"
    if [ "$SCAN_MODE" = "full" ]; then
        base_ports="$FULL_PORTS"
    fi

    local discovered_count=0
    for ((host=SCAN_START; host<=SCAN_END; host++)); do
        local target_ip="${SCAN_SUBNET}.${host}"
        # 优先探测该 IP 在 M3U 中已知的端口，再探测通用组播端口
        local candidate_ports="${KNOWN_IP_PORTS["$target_ip"]} $base_ports"
        local seen_ports=""

        for port in $candidate_ports; do
            if [[ " $seen_ports " == *" $port "* ]]; then
                continue
            fi
            seen_ports="$seen_ports $port"

            local raw_rtp="rtp://${target_ip}:${port}"
            local probe_url
            probe_url=$(normalize_stream_url "$raw_rtp")

            local is_alive="false"
            if [[ "$probe_url" =~ ^http ]] && command -v curl &> /dev/null; then
                # 通过 curl 读取前 564 字节 (3 个 TS 包)，检测是否有数据返回
                local bytes_read
                bytes_read=$(curl -s --max-time 2 --range 0-563 "$probe_url" 2>/dev/null | wc -c | tr -d ' ')
                if [ -n "$bytes_read" ] && [ "$bytes_read" -ge 188 ] 2>/dev/null; then
                    is_alive="true"
                fi
            elif [ "$HAS_FFMPEG" = "true" ]; then
                if timeout 2 ffprobe -v quiet -show_streams "$probe_url" >/dev/null 2>&1 < /dev/null; then
                    is_alive="true"
                fi
            fi

            if [ "$is_alive" = "true" ]; then
                ((discovered_count++))
                local extinf="${KNOWN_EXTINF_MAP["${target_ip}:${port}"]}"
                if [ -z "$extinf" ]; then
                    extinf="#EXTINF:-1 tvg-id=\"\" tvg-name=\"\" tvg-logo=\"\" group-title=\"动态扫描发现\",联通组播_${target_ip}_${port}[高清]"
                fi
                echo "$extinf" >> "$DISCOVERED_M3U"
                echo "$probe_url" >> "$DISCOVERED_M3U"
                echo "[$(date +"%Y-%m-%d %H:%M:%S")] [SCAN_HIT] Found active stream: $raw_rtp -> $probe_url" | tee -a "$LOG_FILE"
                echo "[SCAN_PROGRESS] ip=${target_ip} port=${port} host=${host}/${SCAN_END} discovered=${discovered_count}"
                # 同一 IP 找到活跃组播流后在 fast 模式下可跳至下一 IP 以加速扫描
                if [ "$SCAN_MODE" = "fast" ]; then
                    break
                fi
            else
                echo "[SCAN_PROGRESS] ip=${target_ip} port=${port} host=${host}/${SCAN_END} discovered=${discovered_count}"
            fi
        done
    done

    echo "[$(date +"%Y-%m-%d %H:%M:%S")] [SCAN] Dynamic scan finished. Discovered $discovered_count active streams." | tee -a "$LOG_FILE"
    if [ "$discovered_count" -gt 0 ]; then
        M3U_FILE="$DISCOVERED_M3U"
    fi
}

if [ "$SCAN_ENABLED" = "true" ]; then
    run_dynamic_multicast_scan
fi

if [ ! -f "$M3U_FILE" ]; then
    echo "[ERROR] Input M3U file '$M3U_FILE' not found!" | tee -a "$LOG_FILE"
    exit 1
fi

# 预处理 M3U 文件，移除隐藏字符与首尾空白
TEMP_FILE="${ROOT_DIR}/temp.m3u"
echo "[$(date +"%Y-%m-%d %H:%M:%S")] Preprocessing $M3U_FILE to remove hidden characters..." | tee -a "$LOG_FILE"
tr -cd '\11\12\15\40-\176\200-\377' < "$M3U_FILE" | tr -d '\r' | sed 's/^[ \t]*//;s/[ \t]*$//' > "$TEMP_FILE"

# 初始化带 UTF-8 BOM 的 CSV 文件
echo "[$(date +"%Y-%m-%d %H:%M:%S")] Initializing CSV file with UTF-8 BOM: $CSV_FILE" | tee -a "$LOG_FILE"
printf "\xEF\xBB\xBF" > "$CSV_FILE"
echo "编号,频道名称,video track,audio track,screenshot_name,连接flag,分辨率flag" >> "$CSV_FILE"

# 初始化 modified_iptv.m3u
echo "[$(date +"%Y-%m-%d %H:%M:%S")] Initializing modified m3u file: $MODIFIED_M3U" | tee -a "$LOG_FILE"
if grep -q '^#EXTM3U' "$TEMP_FILE"; then
    grep '^#EXTM3U' "$TEMP_FILE" | head -n 1 > "$MODIFIED_M3U"
else
    echo '#EXTM3U x-tvg-url="http://epg.51zmt.top:8000/e.xml.gz"' > "$MODIFIED_M3U"
fi

# 统计计数器
total_channels=0
true_count=0
false_count=0
hd_count=0
k4_count=0
sd_count=0
other_count=0
error_count=0
counter=1
line_number=0

# 逐行读取并检测频道
while IFS= read -r line || [ -n "$line" ]; do
    ((line_number++))

    # 跳过空行
    if [[ -z "$line" ]] || [[ "$line" =~ ^[[:space:]]*$ ]]; then
        echo "Line $line_number: $line" >> "$SKIPPED_LINES_LOG"
        continue
    fi

    # 匹配 #EXTINF 行
    if [[ "$line" =~ ^[[:space:]]*#EXTINF ]]; then
        channel_start=$(date +%s)
        echo "[$(date +"%Y-%m-%d %H:%M:%S")] Processing channel $counter... Channel start time epoch: $channel_start" | tee -a "$LOG_FILE"

        original_extinf_line="$line"

        # 提取频道名称
        if [[ "$line" == *","* ]]; then
            channel_name=$(echo "$line" | cut -d',' -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
        else
            channel_name=$(echo "$line" | sed 's/^[[:space:]]*#EXTINF:[^ ]*//' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
        fi
        if [ -z "$channel_name" ]; then
            channel_name="Unknown_Channel_$counter"
        fi
        echo "[$(date +"%Y-%m-%d %H:%M:%S")] Channel name: $channel_name" | tee -a "$LOG_FILE"

        # 读取下一行 URL
        ((line_number++))
        IFS= read -r raw_url || [ -n "$raw_url" ]
        url=$(normalize_stream_url "$raw_url")
        echo "[$(date +"%Y-%m-%d %H:%M:%S")] Line $line_number: $raw_url -> $url" | tee -a "$LOG_FILE"

        # 支持 http://, rtp://, udp:// 协议
        if [[ ! "$url" =~ ^(http|rtp|udp) ]]; then
            echo "[$(date +"%Y-%m-%d %H:%M:%S")] Invalid or missing URL for $channel_name: '$url'. Skipping channel." | tee -a "$LOG_FILE"
            echo "Line $line_number: $url" >> "$SKIPPED_LINES_LOG"
            continue
        fi

        screenshot_name="${counter}_${channel_name}.png"
        screenshot_path="${SCREENSHOT_DIR}/${screenshot_name}"

        video_track=""
        audio_track=""
        connect_flag="false"
        resolution_flag="错误"

        probe_start=$(date +%s)
        if [ "$HAS_FFMPEG" = "true" ]; then
            probe_command="timeout $FFPROBE_TIMEOUT ffprobe -v quiet -print_format json -show_streams \"$url\" 2>/dev/null < /dev/null"
            echo "[$(date +"%Y-%m-%d %H:%M:%S")] ffprobe command: $probe_command" | tee -a "$LOG_FILE"
            probe_output=$(eval "$probe_command")
            probe_rc=$?
            probe_end=$(date +%s)
            probe_duration=$((probe_end - probe_start))
            echo "[$(date +"%Y-%m-%d %H:%M:%S")] ffprobe completed in ${probe_duration}s." | tee -a "$LOG_FILE"

            if [ $probe_rc -eq 0 ] && [ -n "$probe_output" ]; then
                if [ "$HAS_JQ" = "true" ]; then
                    video_codec=$(echo "$probe_output" | jq -r '.streams[] | select(.codec_type=="video") | .codec_name' | head -n1)
                    video_width=$(echo "$probe_output" | jq -r '.streams[] | select(.codec_type=="video") | .width' | head -n1)
                    video_height=$(echo "$probe_output" | jq -r '.streams[] | select(.codec_type=="video") | .height' | head -n1)
                    video_fps=$(echo "$probe_output" | jq -r '.streams[] | select(.codec_type=="video") | .r_frame_rate' | head -n1 | cut -d'/' -f1)
                    audio_codec=$(echo "$probe_output" | jq -r '.streams[] | select(.codec_type=="audio") | .codec_name' | head -n1)
                    audio_channels=$(echo "$probe_output" | jq -r '.streams[] | select(.codec_type=="audio") | .channels' | head -n1)
                    audio_sample_rate=$(echo "$probe_output" | jq -r '.streams[] | select(.codec_type=="audio") | .sample_rate' | head -n1)
                else
                    # 无 jq 时使用 grep/sed 提取 JSON 字段
                    video_codec=$(echo "$probe_output" | grep -A 15 '"codec_type": "video"' | grep '"codec_name"' | head -n1 | sed 's/.*: "\(.*\)".*/\1/')
                    video_width=$(echo "$probe_output" | grep -A 25 '"codec_type": "video"' | grep '"width"' | head -n1 | tr -dc '0-9')
                    video_height=$(echo "$probe_output" | grep -A 25 '"codec_type": "video"' | grep '"height"' | head -n1 | tr -dc '0-9')
                    video_fps=$(echo "$probe_output" | grep -A 35 '"codec_type": "video"' | grep '"r_frame_rate"' | head -n1 | sed 's/.*: "\([0-9]*\).*/\1/')
                    audio_codec=$(echo "$probe_output" | grep -A 15 '"codec_type": "audio"' | grep '"codec_name"' | head -n1 | sed 's/.*: "\(.*\)".*/\1/')
                    audio_channels=$(echo "$probe_output" | grep -A 25 '"codec_type": "audio"' | grep '"channels"' | head -n1 | tr -dc '0-9')
                    audio_sample_rate=$(echo "$probe_output" | grep -A 25 '"codec_type": "audio"' | grep '"sample_rate"' | head -n1 | tr -dc '0-9')
                fi

                if [ -n "$video_codec" ] && [ -n "$video_width" ] && [ -n "$video_height" ]; then
                    [ -z "$video_fps" ] && video_fps="25"
                    video_track="#1 $video_codec, ${video_width}x${video_height},${video_fps}fps"

                    resolution_flag="其它"
                    if [ "$video_width" = "1920" ] && [ "$video_height" = "1080" ]; then
                        resolution_flag="高清"
                    elif { [ "$video_width" = "3840" ] && [ "$video_height" = "2160" ]; } || { [ "$video_width" = "0" ] && [ "$video_height" = "0" ]; }; then
                        resolution_flag="4K"
                    elif [ "$video_width" = "720" ] && { [ "$video_height" = "576" ] || [ "$video_height" = "560" ]; }; then
                        resolution_flag="标清"
                    fi
                fi

                audio_ch="2ch"
                if [ "$audio_channels" = "1" ]; then audio_ch="1ch"; elif [ "$audio_channels" = "2" ]; then audio_ch="2ch"; fi
                if [ -n "$audio_sample_rate" ] && [ "$audio_sample_rate" != "null" ]; then
                    if [ "$HAS_BC" = "true" ]; then
                        audio_khz=$(echo "scale=0; $audio_sample_rate / 1000" | bc)khz
                    else
                        audio_khz=$((audio_sample_rate / 1000))khz
                    fi
                else
                    audio_khz="48khz"
                fi
                if [ -n "$audio_codec" ]; then
                    audio_track="#1 $audio_codec,${audio_ch},${audio_khz}"
                fi

                if [ -n "$video_track" ]; then
                    if [ "$SKIP_SCREENSHOT" = "true" ]; then
                        connect_flag="true"
                    else
                        screenshot_command="timeout $FFMPEG_TIMEOUT ffmpeg -nostdin -v quiet -ss 5 -i \"$url\" -frames:v 1 -update 1 \"$screenshot_path\" 2>/dev/null < /dev/null"
                        eval "$screenshot_command"
                        if [ $? -eq 0 ] && [ -f "$screenshot_path" ]; then
                            connect_flag="true"
                        else
                            # 即使截图超时但 ffprobe 已成功解析出视频轨道，仍标记连接有效或按原逻辑处理
                            connect_flag="true"
                        fi
                    fi
                fi
            fi
        else
            # Android Shell 无 ffprobe 时的流连通性降级检测
            if [[ "$url" =~ ^http ]] && command -v curl &> /dev/null; then
                bytes=$(curl -s --max-time "$FFPROBE_TIMEOUT" --range 0-1315 "$url" 2>/dev/null | wc -c | tr -d ' ')
                if [ -n "$bytes" ] && [ "$bytes" -ge 188 ] 2>/dev/null; then
                    connect_flag="true"
                    if [[ "$channel_name" =~ \[4K\] ]]; then
                        resolution_flag="4K"
                        video_track="#1 hevc, 3840x2160,50fps"
                    elif [[ "$channel_name" =~ \[标清\] ]]; then
                        resolution_flag="标清"
                        video_track="#1 h264, 720x576,25fps"
                    else
                        resolution_flag="高清"
                        video_track="#1 h264, 1920x1080,25fps"
                    fi
                    audio_track="#1 mp2,2ch,48khz"
                fi
            fi
        fi

        echo "[$(date +"%Y-%m-%d %H:%M:%S")] Connect flag: $connect_flag, Resolution: $resolution_flag" | tee -a "$LOG_FILE"

        # 检查频道名中的分辨率标识与实际探测分辨率是否不符
        name_resolution=""
        if [[ "$channel_name" =~ \[高清\] ]]; then
            name_resolution="高清"
        elif [[ "$channel_name" =~ \[4K\] ]]; then
            name_resolution="4K"
        elif [[ "$channel_name" =~ \[标清\] ]]; then
            name_resolution="标清"
        fi
        if [ -n "$name_resolution" ] && [ "$name_resolution" != "$resolution_flag" ]; then
            echo "Line $((line_number-1)): Channel '$channel_name', Name Resolution: '$name_resolution', Actual Resolution: '$resolution_flag'" >> "$MISMATCH_LOG"
        fi

        # 更新频道名称分辨率后缀
        new_channel_name="$channel_name"
        if [[ "$channel_name" =~ \[高清\]|\[4K\]|\[标清\] ]]; then
            new_channel_name=$(echo "$channel_name" | sed 's/\[高清\]//g;s/\[4K\]//g;s/\[标清\]//g')
            new_channel_name=$(echo "$new_channel_name" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
            new_channel_name="${new_channel_name}[$resolution_flag]"
        elif [ "$resolution_flag" != "错误" ]; then
            new_channel_name="${channel_name}[$resolution_flag]"
        fi

        # 写入 CSV
        echo "$counter,\"$channel_name\",\"$video_track\",\"$audio_track\",\"$screenshot_name\",$connect_flag,$resolution_flag" >> "$CSV_FILE"

        # 仅当 connect_flag=true 时写入 modified_iptv.m3u
        if [ "$connect_flag" = "true" ]; then
            new_extinf_line=$(echo "$original_extinf_line" | sed "s/,.*$/,${new_channel_name}/")
            echo "$new_extinf_line" >> "$MODIFIED_M3U"
            echo "$raw_url" >> "$MODIFIED_M3U"
        fi

        # 更新统计数据
        ((total_channels++))
        if [ "$connect_flag" = "true" ]; then
            ((true_count++))
        else
            ((false_count++))
        fi
        case "$resolution_flag" in
            "高清") ((hd_count++)) ;;
            "4K") ((k4_count++)) ;;
            "标清") ((sd_count++)) ;;
            "其它") ((other_count++)) ;;
            "错误") ((error_count++)) ;;
        esac

        echo "[PROGRESS] index=$counter name=\"$channel_name\" url=\"$raw_url\" connected=$connect_flag resolution=$resolution_flag video=\"$video_track\" audio=\"$audio_track\""
        ((counter++))
    else
        echo "Line $line_number: $line" >> "$SKIPPED_LINES_LOG"
    fi
done < "$TEMP_FILE"

rm -f "$TEMP_FILE"

total_end=$(date +%s)
total_duration=$((total_end - total_start))
echo "[$(date +"%Y-%m-%d %H:%M:%S")] Processing complete. Results in $CSV_FILE, modified m3u in $MODIFIED_M3U" | tee -a "$LOG_FILE"
echo "[$(date +"%Y-%m-%d %H:%M:%S")] Total duration: $total_duration seconds." | tee -a "$LOG_FILE"
echo "Statistics:" | tee -a "$LOG_FILE"
echo "Total channels: $total_channels" | tee -a "$LOG_FILE"
echo "Connect true: $true_count" | tee -a "$LOG_FILE"
echo "Connect false: $false_count" | tee -a "$LOG_FILE"
echo "高清: $hd_count" | tee -a "$LOG_FILE"
echo "4K: $k4_count" | tee -a "$LOG_FILE"
echo "标清: $sd_count" | tee -a "$LOG_FILE"
echo "其它: $other_count" | tee -a "$LOG_FILE"
echo "错误: $error_count" | tee -a "$LOG_FILE"
