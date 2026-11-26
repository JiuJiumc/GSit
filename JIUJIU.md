# GSit jiujiu 分支

基于原上游 [gecolay/GSit](https://github.com/gecolay/GSit) **3.8.0**，上游提交 `04b792026acf95b63f198691d85218a1023e2f92`。本地维护版为 `3.8.0-jiujiu.4`，保留原插件名 GSit、API、数据目录与作者署名。

## 2026-11-23 维护范围

开发基线为 `https://github.com/33444dirge/GSit.git` 的 `jiujiu` 分支，提交 `fece30969c2cc372d5973a3b5b8fd5cd12a5b362`。从只读复刻目录复制到独立修改工程，原复刻目录不变。用户于2026-11-23在Mizuki 26.2 f44c27e运行内部3.8.0-jiujiu.4后确认“没问题了”。本地26.2/26.3双适配构建与240项检查通过；26.3未单独完成真人客户端验收。

- 26.2 空手右键空气不会发送普通 use-item 包：抱起期间仅给抱起者发送客户端 Interaction 目标，通过 Paper 的未知实体交互事件进入原投掷管线。不会生成世界实体、修改背包或注入网络处理器；只接收当前携带会话的主手右键，退出/传送/放下/抛出/禁用时清除。
- `/dz` 沿用原物品菜单，新增“允许别人骑我”和“允许别人抱起我”，分别显示状态和保存。旧互动偏好首次迁移为两个相同的值，之后互不影响；不限制自己骑人或抱人。
- 原 `/carry on|off|toggle` 仍为两个开关的合并控制；新增 `/carry ride on|off|toggle` 和 `/carry pickup on|off|toggle` 可单独控制。菜单为主要操作入口。
- 旧 `jiujiu-menu.yml` 通过内置默认值补齐新按钮，不覆盖用户配置；默认槽位38、42（从0起），旧配置有重复/越界槽位会记录警告，需要合并自定义布局。
- `.3` 修复 `.2` 仅 setDefaults 后 getInt(path, -1) 仍返回 -1 导致跳过按钮的问题；实际补齐缺少的叶子配置，不覆盖原菜单设置。回归复现用户旧配置的读取方式。
- 类型黑名单拒绝与实际尺寸超限分开提示。配套用户配置仅从其黑名单删除 COW，保留其他原有禁用类型；插件代码不会擅自绕过黑名单。
- 对仍有效的生物携带会话增加可配置的意外下乘保护和每会话一次的来源诊断；主动放下、抛出、传送、死亡、终止清理可正常拆乘。核心强制不可取消的下乘仍被尊重。用户反馈携带立即脱离后，运行日志确认了下文输入目标初始化异常导致的回滚；最终内部.4已获用户确认正常。
- 用户要求保持交付文件名：内部版本 `.4` 的 JAR 对外交付为 `GSit-3.8.0-jiujiu.2.jar`；通过 `/version GSit` 确认真实版本，不凭文件名判断内容。

- `.4` 根据用户 Mizuki 26.2 f44c27e 实际日志修正：旧 CarryInputTarget 构造未入世界的 Interaction 并调用 CraftInteraction.setInteractionWidth 时，实体尚在原点，触发非归属区域访问异常，随后 establish 回滚携带。现完全移除该实体对象，使用本区玩家上下文分配实体ID并直接发送生成、元数据、移动与移除包；不通过Bukkit虚拟实体或绕开已入世界实体的归属检查。26.2/26.3 Interaction宽高协议索引8/9由固定依赖字节码核对。
- 输入辅助初始化发生异常时仅停用该辅助并记录一次完整错误，正常携带仍可成功；抛出方块/实体输入及双Shift放下保留。最终内部.4在用户Mizuki 26.2环境获确认正常；26.3仅完成构建与本地协议/行为检查。

本工程包含完整构建所需的维护源码；`PlayerSitService` 等已有维护部分包含从旧版恢复并改写的代码，不属于完整未修改的作者原始源码。`compile-libs`、`native-api` 和 Gradle Wrapper JAR 仅供编译，不是本插件的安装成果。

此次将本地 `3.7.0-jiujiu.6` 的维护源码移植到完整上游源码树，不再要求旧的已打补丁 JAR，不再使用 PowerShell 或字节码合并构建器。原始本地工程已在工作区外备份。

## 上游 3.8.0 更新

- 新增 `crawltoggle` PlaceholderAPI 占位符和 Paper 更新通知可点击链接。
- 改善姿势、方块事件、偏好查询、MiniMessage 中的旧颜色与 RGB 解析。
- 修复重复药水效果下姿势玩家可见、`/crawl toggle` 误执行动作、GriefPrevention 领地判定和同一方块多人座椅/姿势追踪。
- 修复 Folia 实体清理的线程归属；完整保留上游各版本 NMS/数据包适配源码。

## 保留的定制

- `/dongzuo`、`/dz` 动作菜单、动作自动触发偏好以及座位切换限制。
- `/carry`、`/sq`、`/jiujiuqi`、`/baobao` 抱起、投掷、双 Shift 放下、互动开关、状态及配置重载。
- 原生传送前拆乘、区域归属校验、去重、冷却、容量限制与生命周期清理。
- 异步偏好存储、旧数据库表、UUID 校验、JDBC 资源释放与连接替换时的关闭处理。
- 更新检查的限时请求、响应上限、成功/失败缓存、查询合并、停用清理及实体调度通知，并合入 3.8.0 的可点击更新链接。
- 保留普通箱子动作菜单；旧 `/carry menu` 仍转接现有 `JiuJiuLib` 的 `jiujiulib:jjgui open interaction`。本次不交付或修改该外置菜单；新增两项位于 GSit 自带 `/dz`，不依赖它。

自定义互动命令、权限和联动名称已统一为 JiuJiu 前缀。GSit 已有偏好表与玩家数据继续沿用；旧外部菜单的命令入口应改为 `/carry`。原主配置、数据库和自定义菜单不由构建覆盖。

## 构建

使用 JDK 25、项目自带的 Gradle wrapper。针对当前使用的 26.2、26.3 适配构建：

```bat
gradlew.bat build -PjiujiuVersions=v26_2,v26_3 --console=plain --max-workers=2 --no-configuration-cache
```

依赖缓存齐全后可加 `--offline`。构建产物为 `build/libs/GSit-3.8.0-jiujiu.4.jar`；依用户固定文件名约定，交付复制为 `D:/Github/复刻后改过的jar/GSit-3.8.0-jiujiu.2.jar`。子目录中为上游各发布渠道的描述文件变体。指定版本列表的成品只包含所选适配，不能当作覆盖上游全部 Minecraft 版本的通用包。

不指定 `jiujiuVersions` 时默认构建 26.2、26.3。完整上游各版本源码仍保留在 `mcv`，本轮没有验证旧 Minecraft 版本；本定制版要求 Java 25。26.2/26.3 的 Paper 输入在 `gradle.properties` 中固定版本，以便重建。

`check` 会运行携带、输入去重与清理、菜单开关、真实 SQLite 迁移及重启保存等回归。编译/自动检查不等于真人客户端或正式服验收。`compile-libs` 和 `native-api` 均为仅编译/测试依赖，不打入最终 JAR；来源和 SHA256 见各目录的 `SOURCES.md`。

## 运行边界

Folia 骑人和抱起仍要求已有的 Luminol/Shiroha/Mizuki `EntityTeleportAsyncEvent` 能力；缺失时安全关闭这两项功能，保留其他动作。普通 Paper 路径使用 Paper 传送监听。升级插件请停服更换 JAR 并完整重启；`/carry reload` 仅重载抱起配置。本地完成构建与240项行为/协议检查，用户随后确认Mizuki 26.2实际运行正常；未独立验收26.3客户端或其他核心。
