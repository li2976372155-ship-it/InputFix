# 安装

四种方式，按你的情况选。

---

## 最简单：三步

### ① 装 APK

把 `InputFix.apk` 传到手机，点开安装。

装完**打开一次**，让它在状态栏挂上「InputFix 已就绪」。这一步注册服务、启动后台。

### ② 启用输入法

```
设置 → 系统 → 语言和输入法 → 输入法管理 → 打开「InputFix」
```

**不要设为默认**。你平时继续用搜狗。

### ③ 装技能（关键）

把本仓库的文件解压到手机：

```
/sdcard/Download/InputFix/
├── InputFix.apk
├── SKILL.md
├── AGENT.md
├── ixfile.py
└── install.sh
```

然后在 **dsh-go 的对话里**（它内置了 bash）跑：

```bash
sh /sdcard/Download/InputFix/install.sh
```

看到 `✅ 完成` 就好了。

**开个新对话问它「你有 inputfix-fill 技能吗」，能回答就说明生效了。**

---

## 为什么第③步要在 App 里跑

DSH 的技能目录（`~/.dsh/skills/`）在 dsh-go 的 proot 文件系统里，adb 和文件管理器都进不去。只有 App 自己的 shell 能写。

所以你不需要敲代码，只要复制那一行命令给它。

---

## 存储权限（可选）

如果 App 状态页显示「存储权限不足」，跑这几条：

```bash
adb shell appops set com.dsh.inputfix MANAGE_EXTERNAL_STORAGE allow
adb shell cmd deviceidle whitelist +com.dsh.inputfix
adb shell am set-standby-bucket com.dsh.inputfix active
```

没有电脑的话，也可以去 `设置 → 应用管理 → InputFix → 权限` 手动给。

**注意**：只开存储权限就够了，**不要开无障碍**。`fill` 不需要它。

---

## 从源码构建

需要 JDK 17 + Android SDK 34。

```bash
git clone https://github.com/li2976372155-ship-it/InputFix.git
cd InputFix
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

没有 Gradle 环境的话，用 `tools/build-manual.ps1`（只需 JDK + build-tools）。

---

## 用起来的完整流程

```
你：帮我做学习通这页的填空题

AI：python3 .../ixfile.py status        ← 确认活着
    python3 .../ixfile.py prepare       ← 弹通知
    屏幕顶部有个通知，点一下选 InputFix

你：（点通知 → 选 InputFix）

AI：开始填……第 1 题、第 2 题……
    全做完了，切回搜狗

你：回到搜狗打微信
```

**你只点那一下。**

---

## 常见问题

**Q：能不能全自动，非要我点？**

Android 16 不允许 App 自己改输入法（`WRITE_SECURE_SETTINGS` 授不了），也不允许无障碍点系统选择器（安全对话框拒绝手势）。这步绕不过。但我们把它压缩成「点一次通知」。

**Q：要不要开无障碍？**

**不需要。** InputFix 靠 IME 输入，不靠无障碍。AI 自己有无障碍，用它自己的。

**Q：填完再看是空的？**

跑 `python3 /sdcard/Download/InputFix/ixfile.py status`，看 `ime.imeReady`。是 `false` 说明输入法没切过来。

**Q：App 老是被杀？**

清后台时别划掉它。死了也没关系——`AGENT.md` 里让 AI 遇到超时先 `open com.dsh.inputfix` 把它拉起来。

**Q：能填原生 App 的输入框吗？**

能，但不必要。原生 `EditText` 用普通方式就能填，`commitText` 只是更可靠。

---

## 卸载

```bash
adb shell ime disable com.dsh.inputfix/.FixIme
adb uninstall com.dsh.inputfix
rm -rf ~/.dsh/skills/inputfix-fill
rm -rf /sdcard/Download/InputFix
```
