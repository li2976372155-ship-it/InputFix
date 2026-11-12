# 为什么必须是输入法

这份文档解释一个反复被问到的问题：**为什么不能用无障碍直接往输入框里写字？**

答案不是"无障碍不够强"，而是**事件模型**的问题。

---

## WebView 输入框的两份状态

Android WebView 里的一个 `<input>`，在系统眼里有两个互不同步的副本。

```
┌─────────────────────────────────────────────────────────┐
│  Java 侧                                                 │
│  ┌───────────────────────────────────────┐               │
│  │  Editable（WebView 的 IME 桥缓存）      │               │
│  │  截图能看到字 → 但 Blink 什么都不知道    │               │
│  └───────────────────────────────────────┘               │
│                    ↕（只有 IME 能打通）                    │
│  ┌───────────────────────────────────────┐               │
│  │  Blink: HTMLInputElement.value         │               │
│  │  改了它 → dispatch input 事件 → 页面认账 │               │
│  └───────────────────────────────────────┘               │
└─────────────────────────────────────────────────────────┘
```

**关键**：页面 JS 监听的是 `input` 事件，而这个事件只有 Blink 的编辑命令才会发出。

## 三条路径，只有一条有效

### ❌ `ACTION_SET_TEXT`

```java
Bundle b = new Bundle();
b.putCharSequence(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "答案");
node.performAction(ACTION_SET_TEXT, b);
```

- 直接改 Java 侧 `Editable`
- **Chromium 的 WebView 无障碍桥对它的支持很不完整**，很多版本直接返回 `false`
- 即使返回 `true`，也不一定走到 Blink

### ❌ 剪贴板粘贴

```java
clipboard.setPrimaryClip(ClipData.newPlainText(null, "答案"));
node.performAction(ACTION_PASTE);
```

- 没有**活动的 IME 会话**时，`Editor.paste()` 只作用于 Java 侧
- 不产生 `InputEvent(inputType=insertFromPaste)`

### ❌ `adb shell input text`

- 走 `InputManager.injectInputEvent`，确实是真实按键
- **只支持 ASCII**，中文直接丢
- 而且需要 `INJECT_EVENTS` 权限（MIUI 默认关闭）

### ✅ `InputConnection.commitText()`

```java
InputConnection conn = imeService.getCurrentInputConnection();
conn.commitText("中", 1);
```

- 落到 Blink 的 `InsertTextCommand`
- **必然 dispatch `input`**
- 支持任意 Unicode
- 不需要权限，不需要坐标

## 为什么只有 IME 拿得到 `InputConnection`

`InputConnection` 是 IME 和编辑框之间的**双向通道**，由 `InputMethodManagerService` 绑定。

```java
// 第三方 App 调这个 —— 恒为 null
AccessibilityNodeInfo.getInputConnection()
```

无障碍拿不到它，因为那需要 **IME 身份**（`BIND_INPUT_METHOD` 权限 + 被用户选中）。

这是设计使然，不是 bug。

## 那"光标闪烁"是怎么回事

用户常观察到：**只有光标在输入框里闪烁时，点下一题才保存得住**。

这个观察非常准，它指向的就是上面那条唯一的通路。

- caret blink 由 `Editor` 的 `Caret` 组件驱动
- 只在 `mEditable` 拿到焦点 **且 IME 真的接上** 时才闪

**光标在闪 ⟺ IME 会话成立 ⟺ editing 命令真的走了 Blink**

反过来，用 `ACTION_SET_TEXT` 灌字时，框里有字但光标不闪——因为那不是编辑会话，只是渲染结果。

## `kick` 机制：兼容只认 key 事件的老框架

有些 H5 框架（老版本 jQuery、自研实现）监听的是 `keydown` / `keyup`，对 `input` 免疫。

`InputMethodService` 可以发真实按键：

```java
conn.sendKeyEvent(new KeyEvent(ACTION_DOWN, KEYCODE_DEL));
conn.sendKeyEvent(new KeyEvent(ACTION_UP,   KEYCODE_DEL));
```

于是 `kick` 这样做：

```
填完 "ABC"  →  删掉 'C'  →  重新 commit 'C'
              ↓              ↓
      deleteContentBackward  insertText
```

最终文本不变，但产生了两次真实编辑事件。实测的事件序列里能看到它：

```json
["input:insertText", "keyup", ..., "input:deleteContentBackward", "keyup", "input:insertText", "keyup"]
                                              ↑ kick 留下的痕迹
```

## 为什么用 `dispatchGesture` 而不是 `ACTION_CLICK`

```java
// ❌ 只触发部分 JS click，不给焦点、不拉起 IME
node.performAction(ACTION_CLICK);

// ✅ 60ms 真实按压
Gestures with StrokeDescription(path, 0, 60)
```

WebView 的 focus 流程在真实 down/up 事件里才跑完整。少了这一步，输入框不会获得焦点，IME 也就不会 attach。

## 为什么 `selectionEnd` 比 `actual` 更重要

只看"框里有字"会误判——那可能是渲染层有字，页面 state 是空的。

```java
boolean ok = actual.equals(text)              // 值对了
          && selectionEnd == text.length();   // 光标在末尾
```

`selectionEnd == 长度` 说明两件事：

1. 没有 composing region（拼音没上屏的残留）
2. 切题时不会因为 composition 取消而回滚

## 双通道：为什么文件比 HTTP 可靠

很多自动化环境（Termux proot、VirtualXposed 之类）和 Android 应用**网络命名空间隔离**。

现象很有意思：

```
TCP 握手成功 ✅   →   发 HTTP 请求 → 8 秒无响应 ❌
```

因为连上的是**该环境里另一个占着同端口的空壳**，不是你的服务。

但 `/sdcard` 是共享的（FUSE 挂载），所以文件通道永远能通：

```
inbox/<id>.json     ← 写请求
outbox/<id>.json    ← 读结果
```

代价是延迟（250ms 轮询），换来的是**确定性**。

## 一次完整调用的时序

```
脚本                      FileBridge              IME                Blink
 │                            │                    │                   │
 ├─ 写 inbox/fill.json ──────►│                    │                   │
 │                            ├─ 250ms 轮询发现     │                   │
 │                            ├─ Bridge.fill() ───►│                   │
 │                            │                    ├─ commitText('中') ►│
 │                            │                    │                   ├─ dispatch input
 │                            │                    ├─ commitText('心') ►│
 │                            │                    │                   ├─ dispatch input
 │                            │                    ├─ DEL + 回填(最后一字)│
 │                            │                    │                   ├─ deleteContentBackward
 │                            │                    │                   ├─ insertText
 │                            │                    ├─ 回读校验          │
 │                            ├─ 写 outbox/fill.json│                   │
 │◄─ 轮询到结果 ───────────────┤                    │                   │
```

---

## 一句话总结

> 页面只信 Blink 的 `input` 事件，`input` 只由 Blink 的编辑命令产生，
> 而只有 `InputConnection` 能把命令送进 Blink。
>
> **所以这东西必须是个输入法。**
