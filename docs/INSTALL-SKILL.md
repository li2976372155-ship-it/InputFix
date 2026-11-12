# 给 AI 安装 InputFix 技能（一次性）

> 执行一次，之后每个新对话都会自动知道怎么填网页表单，不用再提醒。

## 你要做的

把 `SKILL.md` 装进 DSH 引擎的技能目录。DSH 的本地技能目录是：

```
<dshHome>/skills/<skill-name>/SKILL.md
```

`<dshHome>` 一般是 `~/.dsh`。你可以先确认一下：

```bash
echo "DSH_HOME=$DSH_HOME"
ls -d ~/.dsh 2>/dev/null
```

## 安装

```bash
mkdir -p ~/.dsh/skills/inputfix-fill
cp /sdcard/Download/InputFix/SKILL.md ~/.dsh/skills/inputfix-fill/SKILL.md
ls -l ~/.dsh/skills/inputfix-fill/SKILL.md
```

装好后跑一下验证：

```bash
python3 /sdcard/Download/InputFix/ixfile.py status
```

## 验证是否生效

**开一个新对话**，直接问：

> 你有哪些技能？有没有跟填网页表单相关的？

应该能看到 `inputfix-fill`。**看到就说明配置成功了**，以后不用再提醒。

## 如果 `~/.dsh` 不对

DSH 会按这个优先级找技能目录：

| 优先级 | 位置 |
|---|---|
| 100 | `<项目根>/.dsh/skills` |
| 200 | `<项目根>/.agents/skills` |
| 300 | 自定义目录（`customSkillDirs`） |
| **400** | **`<dshHome>/skills`** ← 一般用这个 |
| 500 | `<agentsHome>/skills` |

把文件放进任意一个都能生效，400 那层是用户级的，最省事。

## 卸载

```bash
rm -rf ~/.dsh/skills/inputfix-fill
```

## 为什么这样就行

DSH 在每个新会话开始时，会把所有技能的 `name` + `description` 注入给模型。模型看到 `inputfix-fill` 的 description 里写着「学习通、问卷星、WebView 表单、填空」，遇到这类任务就会自己加载这个技能。

**不需要你每次提醒。**
