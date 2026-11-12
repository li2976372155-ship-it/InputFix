# 真机验证报告

设备：23113RKC6C（Redmi K60 Ultra / vermeer）· Android 16 · arm64-v8a · 无 root

## 构建链路

没有 Android SDK、没有 Gradle、没有 JDK，全部现拉：

| 组件 | 来源 | 大小 |
|---|---|---|
| Temurin JDK 17.0.21 | 清华镜像 | 182 MB |
| cmdline-tools | dl.google.com | 146 MB |
| platform-34 (`android.jar`) | 腾讯云镜像 | 60 MB |
| build-tools 34.0.0 | 腾讯云镜像 | 56 MB |

`aapt2` 编译资源 → `aapt2 link` 生成 `R.java` + base.apk → `javac` → `jar` → `d8` 出 `classes.dex` → 塞回 apk → `zipalign` → `apksigner`。

**产物**：`dist/InputFix-1.0.apk` · 28.5 KB · v1/v2 已签名

> cmd.exe 在 GBK 代码页下会撕碎含中文的路径（"03-InputFix-WebView填空插件"），
> 所以构建脚本用 PowerShell 编排，并直接用 `java -cp d8.jar` 调 d8，
> 绕开 `d8.bat` 那一层 cmd。

## 安装与启用

```
adb install -r -t InputFix-1.0.apk     → Success
adb shell ime enable com.dsh.inputfix/.FixIme
adb shell ime set    com.dsh.inputfix/.FixIme
```

`/status` 返回：

```json
{"ok":true,"ime":{"imeReady":true,"editor":"com.dsh.inputfix/other",
 "attachedForMs":1582},"a11y":false,"apiPort":8791,"running":true}
```

API 已通。`a11y:false` 是因为 Android 14+ 不再接受 `settings put secure
enabled_accessibility_services` 直接写值，必须用户在设置里手动确认。

## 核心验证：WebView 会不会认账

`TestActivity` 里并排放一个原生 `EditText` 和一个 `WebView`，
JS 侧给 `<input>` 挂了 `input` / `change` / `keyup` 三个监听，
事后用 `window.__dump()` 回读 value 和事件序列。

结果：

```
EDIT: ready=true fillOk=true sel=7/7 view=[原生测试ABC] err=null

WEB : ready=true fillOk=true sel=7/7
      value = "网页测试XYZ"
      events = ["input:insertText","keyup", "input:insertText","keyup",
                "input:insertText","keyup", "input:insertText","keyup",
                "input:insertText","keyup", "input:insertText","keyup",
                "input:insertText","keyup", "input:deleteContentBackward","keyup",
                "input:insertText","keyup"]
      selStart=7 selEnd=7
```

三条结论，全部实证：

1. **`commitText` 逐字上屏确实 dispatch `input` 事件** —— 7 个字符产生 7 次 `input:insertText`
2. **`kick` 有效** —— 序列里那次 `input:deleteContentBackward` 就是"删掉最后一个字再补回"留下的痕迹，专治只认 keydown/keyup 的老框架
3. **光标钉末尾** —— `selStart=selEnd=7`，与文本长度一致，无 composing 残留

对比之下，`ACTION_SET_TEXT` / 剪贴板粘贴在这套页面里 `events` 会是空的 —— 这就是学习通填空"框里有字、答题卡白底"的机制。

## 踩到并解决的坑

| 现象 | 原因 | 处理 |
|---|---|---|
| `adb shell input tap` 全部报 SecurityException | MIUI 未开「USB 调试(安全设置)」，shell 无 `INJECT_EVENTS` | 改用 App 内自测，不依赖 adb 注入 |
| `commitText` 返回 true 但页面为空 | `onStartInput` 时 `currentInputConnection` 可能为 null，旧连接被误当新连接 | 加 `Bridge.onInputStart()` 在新会话开始时清空旧连接 |
| 自测里 IME 不工作 | JS `focus()` 不会触发 `showSoftInput`，IME 从未启动 | 补 `InputMethodManager.showSoftInput(view, SHOW_FORCED)` |
| 设置页点不到「已下载的应用」 | InputFix 的 44dp 面板压在屏幕底部，遮住该项 | 临时切回搜狗再点 |
| cmd 构建脚本乱码 | GBK 代码页 + 中文路径 | 全程 PowerShell，直接调 `d8.jar` |

## 使用方式

手机上的输入法目前已切到 InputFix。要恢复日常打字：

```
adb shell ime set com.sohu.inputmethod.sogou.xiaomi/.SogouIME
```

或者点输入法面板右下角的「切回键盘」。

AI 填空流程见 `dshgo-skill.md`。

## 遗留

- **无障碍未开启**：InputFix 暂时不能自己定位/点击输入框。
  但目标 App（如 AI Agent 客户端）自带无障碍服务且已启用时，由它负责点击，InputFix 只负责输入，链路是完整的。
  要用 `/fields`、`/tap` 才需要手动去「设置 → 辅助功能 → 已下载的应用 → InputFix 定位服务」打开。
- **学习通端到端未跑**：adb 注入被 MIUI 挡住，最后一跳（点开作业页 → 点输入框）需要 Agent 或人手完成。
