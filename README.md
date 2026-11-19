# 云效合并请求 → 飞书卡片 → 一键合并

在飞书群里收到一张卡片，上面写着"谁提交了什么、合到哪个分支"，点「合并」或「拒绝」就直接在云效上执行，不用再打开浏览器找那个合并请求。

这是一个单进程的 Java 服务：接住云效推送的合并请求消息，拼成飞书卡片发到群里，再通过飞书长连接收你点按钮的结果，回头去调云效的合并接口。

---

## 一、它怎么工作

```
① 有人新建合并请求
        ↓
② 云效把这件事推给本服务（代码库 Webhook）
        ↓
③ 本服务调云效接口补全信息（仓库名、分支、最近提交、有没有冲突、评审有没有过）
        ↓
④ 拼成卡片，发到审批群
        ↓
⑤ 你在飞书点「合并」
        ↓
⑥ 飞书通过长连接把"谁点了哪个按钮"推回本服务
        ↓
⑦ 本服务重新查一遍：你是不是群里的人、请求还开着吗、有没有冲突、卡点过了吗
        ↓
⑧ 都过了才弹二次确认，再点一次才真的调云效合并接口
        ↓
⑨ 卡片原地变绿（已合并）或变灰（已拒绝）
```

**第 ⑦ 步是重点**：这个按钮不是绕过评审的后门。点按钮时会把状态重新查一遍，云效的合并接口里还会再拦一次卡点。评审没过、有冲突、请求已关闭，都会被拒绝并告诉你原因。

---

## 二、三条触发链路，选一条

| 方式 | 需要公网吗 | 延迟 | 说明 |
|---|---|---|---|
| **A. 云效代码库 Webhook**（推荐） | 需要 | 毫秒级 | 报文最完整，卡片信息最全 |
| **B. 定时轮询** | **不需要** | 约 1 分钟 | 服务自己定时去云效问"有没有新的待合并请求" |
| C. 云效流水线调接口 | 需要 | 秒级 | 老方案，拿不到合并请求编号，不推荐 |

没有公网 IP、没有备案域名，就走 B，功能完全一样，只是慢一点。两种不要同时开，否则同一个请求你会收到两张卡。

**飞书那一侧不需要公网。** 服务主动连飞书，按钮点击顺着这条连接推回来，所以不用配置回调地址、不用验签、不用域名备案。公网只是给云效推送用的。

---

## 三、跑起来要准备什么

### 3.1 本机环境

| 项目 | 要求 |
|---|---|
| JDK | 8 或 17 都行，**推荐 17**（本项目在 17 上实跑验证过） |
| Maven | 3.6 以上 |

编译产物是 Java 8 字节码，所以 8 和 17 都能直接跑。

```bash
java -version
mvn -v
```

### 3.2 飞书侧（四件事，少一件都跑不通）

在 [飞书开放平台](https://open.feishu.cn/) 创建**企业自建应用**，然后：

1. **开四个权限**（开发者后台 → 权限管理）

   | 权限 | 用来干什么 | 不开会怎样 |
   |---|---|---|
   | `im:chat.members:read`（查看群成员） | 判断点按钮的人是不是审批群里的人 | 群里所有人点按钮都提示"没有合并权限" |
   | `im:chat:readonly`（获取群组信息） | 查机器人在哪些群，用来拿到群 ID | `/test/chats` 查不出群 ID，配置里没法填 `chat-id` |
   | `im:message:send_as_bot`（以机器人身份发消息） | 发卡片 | 发卡片报权限不足 |
   | `contact:contact.base:readonly`（通讯录基本信息） | 把操作人显示成真实姓名 | 卡片上显示"未知用户（末 6 位）" |

   > 通讯录那条，飞书共给了四个可选项，**开任意一条**即可，推荐 `contact:contact.base:readonly`。

2. **开启机器人能力**（添加应用能力 → 机器人）

3. **创建版本并发布**。⚠️ **加了权限不发版等于没加**，这是最容易漏的一步。

4. **把机器人拉进审批群**。

群 ID（`oc_` 开头）从哪来：服务起来后调 `GET /test/chats`，返回列表里找你的群。

### 3.3 云效侧

云效 → 头像 → **个人设置 → 个人访问令牌**，生成 `pt_` 开头的令牌（**默认 7 天过期，创建时把到期时间选长一点**），勾这些权限：

| 权限 | 用来干什么 |
|---|---|
| 代码管理 → 代码仓库 → **只读** | 查仓库名 |
| 代码管理 → 合并请求 → **读 + 写** | 查合并请求、执行合并/关闭 |

> 建议用单独的"合并机器人"账号生成令牌，别用你自己的。否则云效审计日志里所有合并记录都会算成你本人操作。

---

## 四、四个步骤跑起来

### 第 1 步：拿代码

```bash
git clone https://github.com/yst199324/feishu-merge-bot.git
cd feishu-merge-bot
```

### 第 2 步：填配置

配置在 `src/main/resources/application.yml`，本地跑就改这一份。**必填的只有这几项**：

| 配置项 | 填什么 |
|---|---|
| `feishu.app-id` | 飞书应用凭证里的 App ID，`cli_` 开头 |
| `feishu.app-secret` | 同一页的 App Secret |
| `feishu.chat-id` | 审批群的 `oc_` 开头 ID |
| `yunxiao.token` | 云效个人访问令牌，`pt_` 开头 |
| `yunxiao.org-id` | 云效组织 ID，不能留空 |
| `yunxiao.webhook-token` | 自己定一个随机字符串，比如 `mrb-store-2026-abc123` |

其余保持默认即可。几个值得知道的默认值：

- `feishu.allow-group-members: true` —— 审批群里的人都能点按钮，加人减人自动生效，不用维护名单
- `mergebot.test-endpoints: true` —— 打开调试接口 `/test/*`。**部署到公网必须改成 `false`**，否则任何人都能调它让机器人发卡片
- `mergebot.log-webhook-raw: true` —— 把云效推来的报文原文整段打进日志，排错全靠它（报文里有提交人姓名邮箱，日志要外发时改 `false`）

两条容易踩的：

1. **`yunxiao.org-id` 不能留空。** 留空会走另一套接口路径，用 `codeup.aliyun.com` 的组织会查不到数据。
2. **`yunxiao.webhook-token` 不能是空、也不能留着样例值。** 走 Webhook 触发时这一项必须填，否则 `/webhook/yunxiao` 会拒绝所有请求（安全默认值）。只有一种情况可以留空：你走的是轮询，并且把 `mergebot.poll.enabled` 改成了 `true`——两个都没配，服务启动时直接报"没有任何触发方式"然后退出。本地调试随便填个值即可，只要之后云效那边填同一个值。

### 第 3 步：编译

```bash
mvn clean package
```

产物固定叫 `target/merge-bot.jar`（`pom.xml` 里锁了名字，文档里的命令不用跟着版本号改）。

**这一步会顺带跑完所有单元测试**，44 个用例应该全绿（数量会随迭代增加，全绿就行）。只想跑测试、不打包：

```bash
mvn test
```

### 第 4 步：启动

```bash
java -jar target/merge-bot.jar
```

看到这两行就成功了：

```
配置校验通过，开始连接飞书
Started MergeBotApplication in xxx seconds
```

再加一行确认长连接真的连上了：

```
飞书长连接已建立，断线由 SDK 自动重连，本线程保持存活不再新建连接
```

**如果没有这一行，说明长连接没建立**——这时候卡片能发出去，但点按钮不会有任何反应。

---

## 五、确认它真的通了

本地调试接口默认开着（`mergebot.test-endpoints: true`），按这个顺序验，一步一个结论：

```bash
# ① 服务活着吗
curl 127.0.0.1:8081/test/whoami

# ② 飞书配置对吗，群里能找到吗
curl 127.0.0.1:8081/test/chats

# ③ 云效令牌能用吗
curl 127.0.0.1:8081/test/repos

# ④ 发一张卡片到群里（不传参数 = 演示卡，只看样式）
curl 127.0.0.1:8081/test/card

# ⑤ 发一张真实数据的卡片（换成你的仓库 ID 和合并请求编号）
curl "127.0.0.1:8081/test/card?repo=你的仓库ID&mr=合并请求编号"
```

第 ④ 步卡片到了群里，说明飞书这一侧全通。然后在卡片上点一下按钮，服务端日志会出现"收到卡片回调"——出现这行，说明长连接和按钮回调也通了，剩下只有云效参数的事。

云效推送进来之后，随时可以查最近一条报文的处理结果：

```bash
curl 127.0.0.1:8081/test/last-webhook
```

它会告诉你这份报文被怎么处理了（发了卡 / 被忽略 / 哪一步失败），以及报文原文和请求头。

---

## 六、部署到服务器

### 6.1 放什么

```
/你的目录/mergebot/
├── merge-bot.jar            # mvn package 的产物
├── application.yml          # 从 deploy/application.yml.example 复制一份再改
├── start.sh
├── stop.sh
└── restart.sh
```

`deploy/application.yml`（真实配置，含密钥）和 `deploy/*.jar` 都在 `.gitignore` 里，不会进仓库。仓库里只有模板 `application.yml.example`。

### 6.2 改两个地方才能跑

**① 配置文件**

```bash
cp deploy/application.yml.example deploy/application.yml
```

然后填里面的必填项。注意模板里的端口是 **18123**，和本地 `src/main/resources/application.yml` 的 **8081** 不是一个值，按你实际部署的来。

**② 启动脚本里的 JDK 路径**

`deploy/start.sh` 第 31 行写死了：

```bash
JAVA_BIN="/root/jdk/jdk-17.0.17/bin/java"
```

**换成你服务器上真实的 java 路径**，否则会报"找不到 JDK"。用 JDK 8 就把下面那行注释掉、这行改掉。

### 6.3 日常三条命令

```bash
cd /你的目录/mergebot
chmod +x start.sh stop.sh restart.sh   # 第一次要加执行权限

./start.sh      # 启动
./stop.sh       # 停止
./restart.sh    # 重启（改完 jar 或配置都用它）
```

`start.sh` 会做这些检查，不满足直接报错退出，不会稀里糊涂启动：JDK 在不在、jar 在不在、配置在不在、是不是已经在跑了。

日志在 `logs/` 下：

```bash
tail -f logs/merge-bot-info.log      # 业务日志，日常看这个
tail -f logs/merge-bot-error.log     # 只看报错
tail -f logs/console.log             # 启动阶段的输出（logback 生效前的报错只在这里）
tail -f logs/merge-bot-audit.log     # 谁在什么时候点了什么、结果如何，保留一年
```

---

## 七、云效侧：配 Webhook

云效仓库页面 → **设置 → Webhooks → 新建**：

| 项 | 填什么 |
|---|---|
| 类型 | **选「普通 Webhook」**。不要选「飞书机器人通知」——那个是云效自己往群里发消息，绕过了本服务，卡片上的按钮出不来 |
| URL | `http://你的公网IP:18123/webhook/yunxiao` |
| Secret Token | 和 `yunxiao.webhook-token` **完全一致** |
| 触发事件 | **只勾「合并请求事件」**，把「推送事件」取消 |

配完点一下云效的「测试」按钮，服务端日志会出现一行：

```
忽略非合并请求事件: object_kind=push
```

**这是正常的**——测试按钮发的是推送事件，本来就不发卡。看到"忽略"两个字说明链路通了。

### 端口必须放行

这一步最容易卡住。云效的请求进不来，你在云效后台看到的只是"投递失败"，很难猜到是防火墙：

```bash
# 云服务器的安全组要放行 18123（或你配置的端口）
# 服务器上的防火墙也要放行，比如 firewalld：
firewall-cmd --add-port=18123/tcp --permanent
firewall-cmd --reload
```

从外网验证：

```bash
curl -v http://你的公网IP:18123/test/whoami
```

**能返回内容才算通。** 在服务器上 `curl 127.0.0.1` 能通不代表外网能通，这两个是两回事。

---

## 八、没有公网就开轮询

把配置改成：

```yml
mergebot:
  poll:
    enabled: true
    interval-ms: 60000
    repo-ids:
      - "1234567"
    target-branches:
      - "prod"        # 只关心合到 prod 的，留空 = 全部
    repo-names:
      "1234567": store-web-new
```

仓库 ID 从哪来：打开云效任意一个合并请求，地址栏里 `.../repositories/1234567/changeRequests/52`，中间那串数字就是。

服务启动 15 秒后扫第一轮，之后每分钟一轮。功能完全一样，只是延迟一分钟。

---

## 九、配置项完整说明

按用途分组。没列出的保持默认就好。

### 服务本身

| 配置项 | 默认 | 说明 |
|---|---|---|
| `server.port` | 8081 | 监听端口，云效 Webhook 的 URL 用它 |
| `mergebot.test-endpoints` | true | `/test/*` 调试接口开关，**公网部署必须改 false** |
| `mergebot.log-webhook-raw` | true | 是否把报文原文打进日志 |
| `mergebot.use-payload-commit` | true | 优先用报文里的最近提交，省一次接口调用。改 false 会列出全部提交，但令牌要多勾一项「代码比较 → 只读」 |

### 飞书

| 配置项 | 默认 | 说明 |
|---|---|---|
| `feishu.app-id` | 必填 | 应用 ID |
| `feishu.app-secret` | 必填 | 应用密钥 |
| `feishu.chat-id` | 必填 | 审批群 ID，`oc_` 开头 |
| `feishu.allow-group-members` | true | 是否允许审批群成员点按钮 |
| `feishu.allowed-open-ids` | 空 | 固定白名单，和上一条是"或"关系。一般不用填 |
| `feishu.group-member-cache-seconds` | 300 | 群成员判断结果缓存多久。刚被移出群的人最迟这么久之后点不动，刚进群的人立刻能点 |

> `open_id` 是按应用算的：同一个人在别的飞书应用里的 open_id 跟这里不一样。写错了会一直提示没权限。想看自己的 open_id 是多少，在群里随便点一次卡片，提示气泡里会写。

### 云效

| 配置项 | 默认 | 说明 |
|---|---|---|
| `yunxiao.token` | 必填 | 个人访问令牌，`pt_` 开头 |
| `yunxiao.org-id` | 必填 | 组织 ID，留空会查不到数据 |
| `yunxiao.webhook-token` | 必填（走 Webhook 时） | 校验请求来源，要和云效那边填的一致。留空则 `/webhook/yunxiao` 拒绝所有请求；若同时没开轮询，服务启动就直接退出 |
| `yunxiao.web-base` | codeup.aliyun.com | 网页版地址，卡片上的链接用它拼 |
| `yunxiao.domain` | openapi-rdc.aliyuncs.com | 调接口用的地址，一般不用改 |
| `yunxiao.merge-type` | no-fast-forward | 合并方式：`ff-only` / `no-fast-forward` / `squash` / `rebase` |
| `yunxiao.remove-source-branch` | false | 合并后是否删源分支 |

---

## 十、代码结构

```
src/main/java/com/yiyu/mergebot/
├── MergeBotApplication.java      启动类
├── config/                       配置读取、启动前校验（缺关键配置直接不让启动）
├── feishu/
│   ├── FeishuWsRunner.java       飞书长连接（连上之后挂住，不自己重连）
│   ├── FeishuMessageService.java 发卡片、查群成员、查操作人姓名（带缓存）
│   ├── CardFactory.java          拼卡片：待审批卡、结果卡
│   └── CardActionHandler.java    点按钮之后的完整流程（权限→状态→合并→改卡）
├── web/
│   ├── YunxiaoWebhookController.java  接收云效推送（主链路）
│   ├── FlowWebhookController.java     接收流水线调用（备用）
│   └── TestController.java            调试接口 /test/*
├── yunxiao/                      云效接口封装、字段归一化
├── support/                      去重、分布式锁、审计日志、文本工具
└── poll/                         定时轮询待合并请求（没有公网时用）
```

---

## 十一、出问题了看哪里

先说结论：**九成的问题在日志里能直接看到原因**。按这个顺序看：

```bash
tail -f logs/merge-bot-info.log
```

| 现象 | 先查什么 |
|---|---|
| 云效点了测试按钮，日志什么都没有 | 端口没放行，或者云效那边 URL 填错。`curl -v http://公网IP:端口/test/whoami` 从外网测 |
| 日志有"忽略非合并请求事件" | 正常，测试按钮发的是推送事件，不发卡 |
| 群里收不到卡片 | ①`feishu.chat-id` 对不对（`/test/chats` 查）②机器人有没有被拉进群 ③权限发版了没 |
| 卡片能收到，点按钮没反应 | 长连接没连上。日志搜「飞书长连接已建立」；没有这行就是没连上 |
| 点按钮提示"没有合并权限" | `im:chat.members:read` 没开，或者应用没发版 |
| 卡片上操作人显示"未知用户（末 6 位）" | 缺通讯录权限，开 `contact:contact.base:readonly` |
| 报 `403 Current token has no permission to api` | 云效令牌少勾权限，不是账号没权限。改令牌权限即可，令牌值不变、不用重启 |
| 合并报"云效令牌失效或权限不足" | 令牌被删、权限被改、或账号停用。服务会往群里发一条带处理步骤的提醒 |
| 重启服务后卡片上的"已处理"记不住了 | 正常，去重记录存在内存里，重启就清空 |

更细的排错路径（每一项都对应一次真实踩坑）在手册里：`E:\obsidian笔记\工作文档\云效合并请求飞书审批机器人（配置与排错手册）.md`

---

## 十二、几条要知道的边界

- **去重记录和"当前有效卡片"都存在内存里。** 服务重启就清空，所以重启期间云效重推的报文可能重复发卡，历史上发过的卡也不会再自动更新。
- **每次"关闭后重开"都会发一张新卡**，老卡不会失效。点老卡上的按钮仍然会执行（只要那个请求还开着），因为校验看的是请求的真实状态，不是卡片的新旧。
- **两个触发入口不要同时开**（Webhook 和轮询），同一个请求会收到两张卡。
- **debug 接口 `/test/*` 在公网必须关掉。** 它能让机器人往群里发卡片、能查云效数据，等于一个没有门锁的控制台。
