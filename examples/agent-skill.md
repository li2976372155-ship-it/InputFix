# 学习通 / 网页表单填写说明

> 把这份文件的内容给 AI（或存成它的技能），它就知道该怎么正确地往网页输入框里写字。

---

## 什么时候用这个流程

当你要往**网页里的输入框**写文字时，包括：

- 学习通的作业、考试（填空、简答、论述）
- 问卷星、腾讯问卷
- 各类 OA、打卡、审批表单
- 任何 App 里"看起来像网页"的输入框

**判断方法**：目标 App 是学习通、浏览器、或者任何把网页套在 App 里的应用。

---

## 别用这些方式

```
❌ mcp__phone__type        （无障碍 ACTION_SET_TEXT）
❌ 剪贴板粘贴
❌ input text 命令
```

**原因**：这些只改了渲染层，不触发页面的 `input` 事件。结果是**框里有字、看起来填上了，但页面 state 是空的**。切到下一题再回来，框就空了；提交后显示未作答。

---

## 正确流程

工具：`/sdcard/Download/InputFix/ixfile.py`（纯 Python 标准库）

> **重要**：不要试图调 `activate` 自动切输入法，那件事在 Android 16 上做不到，
> 只会白等十几秒然后失败。切换必须让用户手动做。

### 第 0 步：确认 InputFix 还活着（很重要）

```bash
python3 /sdcard/Download/InputFix/ixfile.py status
```

**如果报「等了 20s 没有结果」**，说明 App 被杀了（很常见，用户清后台就会杀掉）。
先把它拉起来：

```
用你的 open 能力打开 com.dsh.inputfix
等 2-3 秒
再跑一次 status
```

`status` 能返回 `{"ok": true, ...}` 才算活。

### 第 1 步：告诉 InputFix「我要填了」

```bash
python3 /sdcard/Download/InputFix/ixfile.py prepare
```

看返回：

```json
{"ok": true, "ready": true,  "needUser": false}   → 已经是 InputFix，跳第 3 步
{"ok": true, "ready": false, "needUser": true }   → 需要用户切，走第 2 步
```

### 第 2 步：请用户手动切输入法

`prepare` 会弹一条通知，点它就能打开系统输入法选择器（**这一步不需要无障碍**）。

跟用户说：

> 屏幕顶部有个通知，点一下，然后选 **InputFix**。切好了告诉我。

**用户也可以长按空格调出切换。** 两条路都行。

然后轮询，直到就绪：

```bash
python3 /sdcard/Download/InputFix/ixfile.py status
```

`ime.imeReady == true` 才继续。**别死等，超过 60 秒就问用户卡在哪了。**

### 第 3 步：点输入框让它获得焦点

用你自己的点击能力（无障碍 tap / 坐标点击）点一下目标输入框。

然后确认：

```bash
python3 /sdcard/Download/InputFix/ixfile.py status
```

`ime.imeReady == true` 且 `ime.editor` 是目标 App 的包名，才算对。

### 第 4 步：写入

```bash
python3 /sdcard/Download/InputFix/ixfile.py fill 0 "答案文字"
```

看返回，**三条全中才算成功**：

```json
{
  "ok": true,
  "written": "答案文字",
  "actual": "答案文字",
  "selectionStart": 4,
  "selectionEnd": 4
}
```

1. `ok == true`
2. `actual` 与你写的完全一致
3. `selectionEnd == 文本长度`

**只看到 `actual` 有值不算** —— 那可能只是渲染层。

### 第 5 步：干完切回去

```bash
python3 /sdcard/Download/InputFix/ixfile.py deactivate
```

**这个能自动**——走的是 `InputMethodService.switchToPreviousInputMethod()`，
不需要任何权限。会自动切回用户原来的输入法，并撤掉那条通知。

---

## 一次任务多道题时

```bash
# 一次 prepare，后面不用重复
python3 ixfile.py prepare

# 每道题
点输入框 → fill → 切下一题

# 全部做完
python3 ixfile.py deactivate
```

---

## 排错

| 返回 | 意思 | 怎么办 |
|---|---|---|
| `等了 20s 没有结果` | InputFix 没运行 | 让用户打开一次 InputFix App |
| `IME 未连接` | 输入框没拿到焦点 | 重新点一下输入框，停 500ms 再 fill |
| `ok:true` 但页面没保存 | 输入法不是 InputFix | 跑 `status` 看 `ime.editor` |
| `selectionEnd` 不等于长度 | 光标不在末尾 | 再 `fill` 一次（自带 kick 补刀） |
| `prepare` 返回 `ready:true` | 已经切好了 | 直接 fill |

---

## 一句话记住

```
prepare → 等 imeReady → 点框 → fill → 看 selectionEnd → deactivate
```

**全程不用 `type`，不用粘贴，不点键盘上的字母。**
