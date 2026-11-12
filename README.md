InputFix
> **一个给 AI Agent 用的「手」。**
>
> [dsh-go](https://github.com/ccch1mneyyy/dsh-go) 这类手机 AI Agent 能理解「帮我填学习通作业」，
> 但没法把字真正写进网页表单——它只能用 `ACTION_SET_TEXT`，改的是渲染层，页面不认账。
>
> InputFix 补上这一环：它以输入法的身份，通过 `commitText()` 逐字上屏，
> 让每个字符都触发页面的 `input` 事件。
>
> **Agent 负责知道什么时候填、填什么；InputFix 负责真的填进去。**
> 两者通过一个技能文件 + 文件通道握手，用户只需要点一次通知。
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-blue)]()
[![API](https://img.shields.io/badge/API-26%2B-brightgreen)]()
[![License](https://img.shields.io/badge/license-MIT-green)]()
[![Size](https://img.shields.io/badge/APK-40.5%20KB-orange)]()
---
它和 AI Agent 的分工
能力	谁提供
知道什么时候要填、填什么	AI Agent（它理解任务）
知道怎么把字真正写进页面	InputFix（IME 通道）
Agent 有前者没后者，InputFix 有后者没前者。所以要握手。
```
dsh-go                    InputFix
──────                    ─────────
识别「这是填空题」    →     prepare：弹通知引导用户切换
用户点通知选 InputFix  →     拿到 InputConnection
点输入框              →     
调 fill               →     commitText 逐字上屏 → 页面收到 input
逐题重复              →     
全部完成              →     deactivate：自动切回搜狗
```
Agent 侧怎么接入：把 `skills/inputfix-fill/SKILL.md` 装进它的技能目录
（`sh tools/install.sh`）。之后每个新对话它都会自动加载，遇到填表单的任务就知道该走这套流程——不需要用户每轮提醒。
完整协议见 `examples/agent-skill.md`。
---
问题
用自动化工具（无障碍、ADB、按键精灵、AI Agent）往网页的输入框里填字，会遇到一个很典型的现象：
```
框里有字 ✅   截图能看到 ✅   点"下一题" ✅   重新打开这题 → 框是空的 ❌
```
在学习通、问卷星、各类在线考试系统上，答案填进去了，但答题卡显示未作答。
根因：Android WebView 里的输入框有两份状态。
位置	谁维护	改了它之后
Java 侧 `Editable`	`AccessibilityNodeInfo` / 剪贴板	截图能看到字，但页面 JS 不知道
Blink `HTMLInputElement.value`	Chromium 编辑引擎	触发 `input` 事件，页面才认账
`ACTION_SET_TEXT`、剪贴板粘贴、`adb shell input text` 走的都是第一条。它们只改了渲染层，不 dispatch `input` 事件，前端框架（React / Vue / 自研）的 state 永远是空的。
切题时页面按 state 重渲染 —— 于是看起来"没做"。
解法
只有 IME 能拿到真正的 `InputConnection`。
```
InputConnection.commitText() → Blink InsertTextCommand → dispatch input ✅
```
`InputFix` 就是一个没有键盘的输入法：它不显示键盘，只有一个 44dp 的状态条，全部工作在后台——通过 `commitText()` 逐字上屏，模拟真实输入法打字。
实测证据（WebView 里的 `<input>` 挂了 `input` 监听）：
```json
{
  "value": "网页测试XYZ",
  "events": ["input:insertText","keyup", "input:insertText","keyup",
             "input:insertText","keyup", "input:insertText","keyup",
             "input:insertText","keyup", "input:insertText","keyup",
             "input:insertText","keyup",
             "input:deleteContentBackward","keyup",   ← kick 机制
             "input:insertText","keyup"],
  "selStart": 7, "selEnd": 7
}
```
7 个字符 → 7 次 `input:insertText`。页面真的收到了编辑命令。
架构
```
┌──────────────────────────────────────────────────────┐
│  InputFix (单 App，三个服务)                          │
│                                                      │
│  FixIme    InputMethodService    ← 唯一输入通道       │
│     └── Bridge.commitText()                            │
│                                                      │
│  FixA11y   AccessibilityService  ← 定位 / 点击 / 截图  │
│     ├── dispatchGesture 60ms 真实触摸                  │
│     ├── takeScreenshot() 截屏（无需录屏授权）            │
│     └── findAccessibilityNodeInfosByText 按字点        │
│                                                      │
│  FileBridge  /sdcard/.../inbox → outbox  ← 文件通道    │
│  Api         127.0.0.1:8791             ← HTTP 通道    │
└──────────────────────────────────────────────────────┘
              ▲                    ▲
              │ curl / python      │ 文件读写
              │                    │
         PC 脚本 / 自动化工具    沙箱化环境（proot 等）
```
双通道设计的原因：很多自动化环境（比如 Termux、proot Ubuntu）和 Android 应用的网络命名空间是隔离的——TCP 能握手但 HTTP 收不到响应。文件通道 `/sdcard` 是共享的，永远能通。
安装
三步，最后一歩是把一行命令发给 AI。
① 装 APK
```bash
adb install -r InputFix.apk
adb shell ime enable com.dsh.inputfix/.FixIme
adb shell appops set com.dsh.inputfix MANAGE_EXTERNAL_STORAGE allow
adb shell cmd deviceidle whitelist +com.dsh.inputfix
```
不要把它设为默认输入法，你平时继续用搜狗。
② 把文件放到手机
```
/sdcard/Download/InputFix/
├── InputFix.apk
├── SKILL.md
├── AGENT.md
├── ixfile.py
└── install.sh
```
③ 装技能（让 Agent 记住怎么用）
在 dsh-go（或任何内置 bash 的 AI 客户端）对话里跑：
```bash
sh /sdcard/Download/InputFix/install.sh
```
脚本会把 `SKILL.md` 装进 DSH 的技能目录，之后每个新对话自动加载，不用再提醒。
> 技能目录（`~/.dsh/skills/`）在 proot 文件系统里，adb 和文件管理器都进不去，
> 只有 App 自己的 shell 能写。所以这一步必须由它执行。
装完开个新对话问「你有 inputfix-fill 技能吗」，能回答就成功了。
详细步骤见 `docs/INSTALL.md`。
从源码构建
```bash
git clone https://github.com/li2976372155-ship-it/InputFix.git
cd InputFix
./gradlew assembleDebug
```
没有 Gradle 环境的话用 `tools/build-manual.ps1`（只需 JDK 17 + build-tools）。
使用
输入法方式（如果直接调用 `commitText`）
安装后切到 InputFix，任何输入框都会走真·上屏。
HTTP 通道
```bash
# 状态
curl -s 127.0.0.1:8791/status

# 填一个框
curl -s -X POST 127.0.0.1:8791/fill \
  -H 'Content-Type: application/json' \
  -d '{"index":0,"text":"以人民为中心","kick":true}'

# 批量
curl -s -X POST 127.0.0.1:8791/answer \
  -H 'Content-Type: application/json' \
  -d '{"answers":[{"index":0,"text":"A"},{"index":1,"text":"B"}]}'
```
文件通道（网络隔离环境用）
```bash
python3 tools/ixfile.py status
python3 tools/ixfile.py fields
python3 tools/ixfile.py tap 0
python3 tools/ixfile.py fill 0 "答案"
python3 tools/ixfile.py screenshot        # 截图给视觉模型看
python3 tools/ixfile.py tapxy 720 417     # 按坐标点
```
纯标准库，不需要 curl，不需要第三方包。
API
HTTP — `127.0.0.1:8791`
方法	路径	说明
GET	`/ping`	存活
GET	`/status`	IME / 无障碍 / 监听地址
GET	`/fields`	屏幕上所有输入框
GET	`/focus`	当前焦点框快照
POST	`/fill`	填一个框
POST	`/answer`	批量填
POST	`/clear`	清空
POST	`/tap`	点输入框 / 按坐标
POST	`/ime/reset`	丢弃旧连接
给 Agent 的协作接口（文件通道）
op	说明
`status`	探活 + 看 `imeReady`
`prepare`	「我要填了」——弹通知引导用户切输入法
`fill`	写入
`deactivate`	切回用户原来的输入法（能自动）
`tapxy` / `taptext`	按坐标 / 按文字点
`screenshot`	截屏给视觉模型看
`deactivate` 走 `InputMethodService.switchToPreviousInputMethod()`，不需要任何权限。
`prepare` 弹的通知是用户切换输入法的入口——这一步必须人点，原因见下。
文件 — `/sdcard/Download/InputFix/`
```
inbox/<id>.json     ← 写请求
outbox/<id>.json    ← 读结果
status.json         ← 实时状态（每 500ms 刷新）
bridge.log          ← 事件流水
```
op：`status` `fields` `focus` `fill` `answer` `tap` `tapxy` `taptext` `clear` `screenshot`
核心机制
1. 逐字 `commitText`
```java
for (char ch : text.toCharArray()) {
    conn.commitText(String.valueOf(ch), 1);   // 每字一次，产生独立 input 事件
    Thread.sleep(45);                          // 模拟人手速度
}
```
不是一次性 `setText` 整段——输入法就是这么走的。
2. `kick` 补刀
部分老框架只监听 `keydown` / `keyup`，对 `input` 免疫。填完末尾补一刀：
```java
deleteBackward(conn, 1);          // 触发 deleteContentBackward
conn.commitText(lastChar, 1);     // 再 insertText 回来
```
最终文本不变，但多了两次真实编辑事件。
3. 三条成功判据
```java
ok && actual.equals(text) && selectionEnd == text.length()
```
`selectionEnd == 长度` 说明光标钉在末尾、无 composing 残留
只看到"框里有字"不算成功——那可能只是渲染层，页面 state 是空的。这是最容易踩的坑。
4. 真实触摸
```java
new GestureDescription.StrokeDescription(path, 0, 60)   // 按住 60ms
```
WebView 的 focus 流程只在真实 down/up 事件里跑完整。`performAction(ACTION_CLICK)` 经常只触发 JS 的 click，不给输入框焦点、不拉起 IME。
已知限制
限制	原因	影响
不能自动切换输入法	Android 16 禁止 adb 授予 `WRITE_SECURE_SETTINGS`；无障碍手势又进不了输入法选择器	需手动切一次，或上 Shizuku
必须 tap 一次输入框	IME 只在有焦点时才有 `InputConnection`	但只需点框，不用点键盘
切到 InputFix 就没正常键盘	它是个"无键盘输入法"	面板留了「切回键盘」按钮
系统对话框点不动	`dispatchGesture` 被安全策略拦截	输入法选择器、权限弹窗都点不了
文件通道需要存储权限	targetSdk 28 + `MANAGE_EXTERNAL_STORAGE`	需 adb appops 授权一次
别用「强制停止」	会连带清掉无障碍注册	用最近任务划掉即可
适用场景
任何 WebView 里的表单都适用：
在线作业 / 考试系统（学习通、超星、智慧树）
问卷（问卷星、腾讯问卷）
OA 审批、打卡
需要"看起来像人在打字"的场合（`delayMs` 可调）
原生 `EditText` 同样有效，只是不需要 `kick`。
测试环境
	
设备	Redmi K60 Ultra (23113RKC6C)
系统	Android 16 / HyperOS
ABI	arm64-v8a
root	无
验证	EditText ✅ · WebView `input` 事件 ✅ · 坐标点击 ✅ · 截图 ✅
详见 `docs/TEST-REPORT.md`。
参考
`docs/ARCHITECTURE.md` — 为什么必须是 IME，为什么只有 `commitText` 有效
`docs/TEST-REPORT.md` — 真机验证全过程
`examples/` — 给 AI Agent 用的操作协议模板
License
MIT
