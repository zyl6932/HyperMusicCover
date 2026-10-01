#!/system/bin/sh
#
# ColorOS 17 lock-screen 高德 metro/bus navigation: everything needed to port it, in one archive.
#
# Run on the OPPO phone, as root:
#     su -c sh /sdcard/Download/coloros17-dump.sh
# (/sdcard is mounted noexec, so run it through sh rather than ./)
#
# It collects the SystemUI side (SystemUI, its plugins, the card engines, framework jars), the
# system's properties, 高德's version and the cloud config in its data that mentions the lock-screen
# map, then walks through one metro/bus navigation on the lock screen while it records the screen and
# dumps which services SystemUI has bound. Everything ends up in
#     /sdcard/Download/coloros17_island_dump.tar.gz
#
# What is NOT collected: the full notification dump and the screen outside the capture window. The
# logcat does carry whatever the phone logged in those minutes - have a look before passing it on.

AMAP=com.autonavi.minimap
DL=/sdcard/Download
OUT=$DL/coloros17_island_dump
ARCHIVE=$DL/coloros17_island_dump.tar.gz
CAPTURE_SECS=90

say() { echo; echo "==> $*"; }
ask() { echo; echo ">>> $*"; echo ">>> 好了以后按回车继续"; read _; }

if [ "$(id -u)" != "0" ]; then
    echo "需要 root：su -c sh $0"
    exit 1
fi

rm -rf "$OUT" "$ARCHIVE"
mkdir -p "$OUT/apk" "$OUT/framework" "$OUT/runtime" "$OUT/amap"
cd "$OUT" || exit 1
exec 3>&1
log() { echo "$*" | tee -a "$OUT/dump.log" >&3; }

# ---------------------------------------------------------------- 1. the system and its packages
say "1/4 系统信息"
getprop > getprop.txt
uname -a > uname.txt
pm list packages -f -U --show-versioncode > packages.txt 2>/dev/null || pm list packages -f > packages.txt

say "2/4 复制 SystemUI 及相关应用（APK + oat）"
# SystemUI, its plugins, and the engines that draw ColorOS's islands and cards (流体云/种子卡片/泛在).
PATTERN='systemui|plugin|livealert|fluid|seedling|island|pantanal|uiengine|smartengine|keyguard|lockscreen|aod'
pm list packages 2>/dev/null | sed 's/^package://' | grep -iE "$PATTERN" | sort -u > picked_packages.txt
echo "$AMAP" >> picked_packages.txt
log "挑出的包："; cat picked_packages.txt >&3

while read -r pkg; do
    [ -z "$pkg" ] && continue
    dumpsys package "$pkg" | grep -E "versionName|versionCode|codePath|resourcePath|flags=" \
        > "apk/$pkg.info.txt" 2>/dev/null
    [ "$pkg" = "$AMAP" ] && continue   # 高德's APK is the same as ours; only its info is kept
    for apk in $(pm path "$pkg" 2>/dev/null | sed 's/^package://'); do
        dir=$(dirname "$apk")
        dest="apk/$pkg$(echo "$dir" | tr '/' '_')"
        [ -d "$dest" ] && continue
        mkdir -p "$dest"
        cp -r "$dir/." "$dest/" 2>/dev/null
        log "  $pkg <- $dir"
    done
done < picked_packages.txt

# The OEM framework jars SystemUI calls into, and the plain ones for reference. Boot images are
# skipped: the jars on recent Android still carry their dex.
for d in /system/framework /system_ext/framework /product/framework /odm/framework /my_product/framework; do
    [ -d "$d" ] || continue
    dest="framework$(echo "$d" | tr '/' '_')"
    mkdir -p "$dest"
    ls -la "$d" > "$dest/_listing.txt" 2>/dev/null
    for f in "$d"/*.jar "$d"/*.apk; do
        [ -f "$f" ] && cp "$f" "$dest/"
    done
done
# Overlays and feature lists that switch these things on per device.
for d in /my_product/etc /my_product/vendor/etc /system_ext/etc /product/etc; do
    [ -d "$d" ] || continue
    dest="framework/etc$(echo "$d" | tr '/' '_')"
    mkdir -p "$dest"
    find "$d" -maxdepth 3 -type f \( -iname '*feature*' -o -iname '*systemui*' -o -iname '*island*' \
        -o -iname '*fluid*' -o -iname '*livealert*' -o -iname '*seedling*' \) \
        -exec cp {} "$dest/" \; 2>/dev/null
done

# ---------------------------------------------------------------- 3. 高德's side
say "3/4 高德的版本和云端配置"
AD=/data/data/$AMAP
dumpsys package $AMAP > amap/package.txt
ls -laR "$AD/shared_prefs" > amap/shared_prefs_listing.txt 2>/dev/null
# Files in 高德's data that mention the lock-screen map or ColorOS checks: the AJX pages and the
# cloud switches. Copied when they are not huge.
grep -rlE "ImmerseNavi|immersenavi|immerse_navi|opporom|isOppo|livealert|LiveAlert" "$AD" 2>/dev/null \
    > amap/matching_files.txt
while read -r f; do
    size=$(stat -c %s "$f" 2>/dev/null || echo 0)
    if [ "$size" -lt 31457280 ]; then
        dest="amap/files$(dirname "$f" | sed "s#^$AD##")"
        mkdir -p "$dest"
        cp "$f" "$dest/"
    fi
done < amap/matching_files.txt
log "  高德数据里匹配到 $(wc -l < amap/matching_files.txt) 个文件"

# ---------------------------------------------------------------- 4. one navigation, on the lock screen
say "4/4 抓地铁/公交导航时的运行状态"
ask "先把高德打开，选好一条地铁或公交路线，停在「开始导航」按钮前（先别点）"
logcat -c 2>/dev/null; logcat -b all -c 2>/dev/null
ask "现在点「开始导航」，进入沿站导航页面"

echo
echo ">>> 按回车后有 $CAPTURE_SECS 秒：马上锁屏，然后在锁屏上"
echo ">>>   1) 等岛/卡片出现  2) 点开它  3) 点一点上面的按钮  4) 停在展开的样子看一会儿"
echo ">>> 屏幕会被录下来。时间到了再解锁回到这里。"
read _

R=runtime
screenrecord --time-limit $CAPTURE_SECS "$R/lockscreen.mp4" >/dev/null 2>&1 &
REC=$!

i=0
while [ $i -lt $CAPTURE_SECS ]; do
    t=$(printf '%03d' $i)
    dumpsys activity services $AMAP > "$R/amap_services_$t.txt" 2>&1
    dumpsys notification --noredact 2>/dev/null \
        | awk '/NotificationRecord\(/{keep=($0 ~ /'"$AMAP"'/)} keep' > "$R/amap_notifications_$t.txt"
    if [ $i -eq 30 ] || [ $i -eq 60 ]; then
        screencap -p "$R/screen_$t.png"
        dumpsys window windows > "$R/window_$t.txt" 2>&1
        dumpsys SurfaceFlinger --list > "$R/surfaces_$t.txt" 2>&1
        dumpsys activity services com.android.systemui > "$R/systemui_services_$t.txt" 2>&1
        dumpsys activity service com.android.systemui > "$R/systemui_dump_$t.txt" 2>&1
        dumpsys activity top > "$R/activity_top_$t.txt" 2>&1
    fi
    sleep 5
    i=$((i + 5))
done
wait $REC 2>/dev/null

say "保存日志"
logcat -b all -d > "$R/logcat_all.txt" 2>&1
dumpsys activity services $AMAP > "$R/amap_services_after.txt" 2>&1
date > "$R/finished_at.txt"

# ---------------------------------------------------------------- pack
say "打包"
cd "$DL" || exit 1
tar czf "$ARCHIVE" "$(basename "$OUT")" 2>/dev/null
rm -rf "$OUT"
ls -la "$ARCHIVE"
echo
echo "完成：$ARCHIVE"
echo "把这个文件发回来就行。"
