---
name: inputfix-fill
description: 往网页表单的输入框里填文字时用这个。学习通、超星、智慧树、问卷星、腾讯问卷、各类在线考试系统、OA 审批打卡等 App 里的输入框，本质是 WebView 里的 H5 表单。用无障碍 type、剪贴板粘贴、input text 都只会改渲染层，不触发页面的 input 事件，结果就是"框里有字但提交后为空"。必须走 InputFix 的 IME 通道才能真正写进去。凡是遇到填空、简答、论述、备注这类需要往网页输入框写字的任务，先加载这个技能。
whenToUse: 任务涉及往 WebView 输入框写中文时
---

# 网页表单填写：必须走 InputFix

## 为什么普通方式不行

Android WebView 里的输入框有两份状态：

| 位置 | 谁维护 | 改了它 |
|---|---|---|
| Java 侧 `Editable` | 无障碍 `ACTION_SET_TEXT`、剪贴板 | 截图能看到字，**页面 JS 不知道** |
| Blink `HTMLInputElement.value` | Chromium 编辑引擎 | 触发 `input` 事件，**页面才认账** |

前两条路只改渲染层。结果是：框里有字、看起来填上了，但**切到下一题再回来就空了**，提交后显示未作答。

**只有 IME 的 `InputConnection.commitText()` 能触发 `input` 事件。**

`InputFix` 就是一个无键盘输入法，专门干这件事。

## 前置：确认它活着

```bash
python3 /sdcard/Download/InputFix/ixfile.py status
```

- 返回 `{"ok": true, ...}` → 活着，继续
- 报「等了 15s 没有结果」→ App 被杀了（用户清后台很常见）

**被杀就拉起来，这里有坑**：

```
open com.dsh.inputfix
sleep 3                          ← 必须等，不要立刻发请求
status                           ← 确认能通再往下走
```

**别 open 完马上发 prepare**，App 冷启动要时间，请求会躺在 inbox 里没人管，
然后你等到超时——而且 App 过一会儿起来时，会把那条陈年请求翻出来执行，
造成莫名其妙的行为。

从报「超时」到「open 完确认 status 通」，中间至少留 3 秒。

## 第一步：请求切换

```bash
python3 /sdcard/Download/InputFix/ixfile.py prepare
```

```json
{"ok": true, "ready": true,  "needUser": false}   → 已经是 InputFix，跳到第三步
{"ok": true, "ready": false, "needUser": true }   → 需要用户切，走第二步
```

## 第二步：请用户手动切输入法

`prepare` 弹了一条通知。告诉用户：

> 屏幕顶部有个通知，点一下，然后选 **InputFix**。切好了告诉我。

**不要调 `activate`。** 自动切换在 Android 16 上做不到，实测：

- `WRITE_SECURE_SETTINGS` 拿不到（Android 16 禁止 adb 授予）
- 无障碍 `dispatchGesture` 能弹出选择器，但**点不动**里面的条目（系统安全对话框不接受无障碍手势）

然后轮询直到就绪：

```bash
python3 /sdcard/Download/InputFix/ixfile.py status
```

`ime.imeReady == true` 才继续。超过 60 秒就问用户卡在哪。

## 第三步：点输入框

用你自己的点击能力点一下目标输入框（无障碍 tap 或坐标点击）。

然后确认：

```bash
python3 /sdcard/Download/InputFix/ixfile.py status
```

`ime.imeReady == true` 且 `ime.editor` 是目标 App 的包名，才算对。

## 第四步：写入

```bash
python3 /sdcard/Download/InputFix/ixfile.py fill 0 "答案"
```

**三条判据全中才算成功**：

```json
{"ok": true, "written": "答案", "actual": "答案",
 "selectionStart": 2, "selectionEnd": 2}
```

1. `ok == true`
2. `actual` 与原文完全一致
3. `selectionEnd == 文本长度`

只看到 `actual` 有值**不算**——那可能只是渲染层。

## 第五步：切回

```bash
python3 /sdcard/Download/InputFix/ixfile.py deactivate
```

**这个能自动**，走 `switchToPreviousInputMethod()`，不需要权限。顺手撤掉那条通知。

## 多题时的节奏

```bash
prepare               # 一次就够
# 每道题：点框 → fill → 切下一题
deactivate            # 全做完
```

## 绝不使用

```
❌ mcp__phone__type     （ACTION_SET_TEXT）
❌ 剪贴板粘贴
❌ input text 命令
❌ activate             （做不到，白等）
```

## 排错

| 返回 | 处理 |
|---|---|
| `等了 20s 没有结果` | open 拉起 App，等 2 秒重试 |
| `IME 未连接` | 重新点输入框，停 500ms 再 fill |
| `ok:true` 但页面没保存 | `status` 看 `ime.editor`，输入法可能没切过来 |
| `selectionEnd` 不等于长度 | 再 fill 一次（自带 kick 补刀） |

## 一句话

```
status → prepare → 用户点通知选 InputFix → 点框 → fill → 核 selectionEnd → deactivate
```
