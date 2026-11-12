#!/system/bin/sh
# InputFix 技能安装脚本
#
# 用法（在 dsh-go 的 bash 里跑）：
#   sh /sdcard/Download/InputFix/install.sh
#
# 它会做两件事：
#   1. 把 SKILL.md 放进 DSH 的技能目录，让每个新对话自动加载
#   2. 检查 APK 和工具脚本是否就位

set -e

SRC="/sdcard/Download/InputFix"

# 找 DSH 的技能根目录
if [ -n "$DSH_HOME" ] && [ -d "$DSH_HOME" ]; then
    ROOT="$DSH_HOME"
elif [ -d "$HOME/.dsh" ]; then
    ROOT="$HOME/.dsh"
elif [ -d "$HOME/.agents" ]; then
    ROOT="$HOME/.agents"
else
    ROOT="$HOME/.dsh"
    echo "[!] 没找到现成的 DSH 目录，按默认路径创建：$ROOT"
fi

DST="$ROOT/skills/inputfix-fill"

echo "==> 源目录: $SRC"
echo "==> 技能目录: $DST"

if [ ! -f "$SRC/SKILL.md" ]; then
    echo "[x] 找不到 $SRC/SKILL.md"
    echo "    请先把 zip 里的文件解压到 /sdcard/Download/InputFix/"
    exit 1
fi

mkdir -p "$DST"
cp "$SRC/SKILL.md" "$DST/SKILL.md"

# 顺手把协议文档也放进去，方便 Agent 需要时翻
[ -f "$SRC/AGENT.md" ] && cp "$SRC/AGENT.md" "$DST/AGENT.md"
[ -f "$SRC/ixfile.py" ] && cp "$SRC/ixfile.py" "$DST/ixfile.py"

echo ""
echo "==> 已安装："
ls -l "$DST"

echo ""
echo "==> 验证技能是否可加载："
if [ -f "$DST/SKILL.md" ]; then
    head -3 "$DST/SKILL.md"
    echo ""
    echo "✅ 完成。开一个新对话，问「你有 inputfix-fill 技能吗」确认。"
else
    echo "❌ 安装失败"
    exit 1
fi

echo ""
echo "==> 检查运行环境："
if command -v python3 >/dev/null 2>&1; then
    echo "  python3: $(command -v python3)"
else
    echo "  [!] 没有 python3，文件通道不可用"
fi

if [ -f "$SRC/ixfile.py" ]; then
    python3 "$SRC/ixfile.py" status 2>/dev/null | head -20 || echo "  [!] InputFix 没在跑，打开一次 App"
fi
