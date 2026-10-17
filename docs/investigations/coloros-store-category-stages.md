> 历史记录：其中分类模式与空白搜索首页已由 [清空软件页与移除空白首页](coloros-store-empty-software.md) 替代，不代表当前实现。

# 分类模式分步移除与隐藏

本轮仅修改 StorePurificationHooks.java 和针对性检查脚本；搜索框边距及其它共享改动不变。不构建、不提交、不操作设备界面。

## 已核实的绑定路径

目标为国行 com.heytap.market 26.9.4_CN / 260904。

- 首次在线/缓存响应：HomeSubTabPresenter#ޱ → ee7.extractSubTabDataFromCards → ދ(SubTabCardGlobalDto,int,boolean)。
- 缓存后的在线响应：main.home.tab.a 回调同样调用 ދ。
- 完整模式：ࡨ 从 DTO 生成页面并插入 firstPagePos 的 HomeChosen → ޟ(List<SubTabDto>,List<CdoFragmentPagerAdapter.a>)。
- ޟ 同时提交 CdoFragmentPagerAdapter#Ϳ 和 ScrollIdleTabLayout#Ϳ；后者经 ee7.bindDataForSubTabLayout 绑定 jf7，显示 SubTabDto.getTitle()。两份列表必须按同一索引过滤。
- SubTabDto 的 id、title、actionParam 是服务端序列化字段（Tag 1/2/6）。原生分类识别使用 oh3 → a6g → y5a/gq2，与标题语言无关。

限制：APK 内置软件主导航的“精选”ID200、“分类”ID201 属于 main.api.model.SubTabDto，不能冒充首页在线 card.domain.dto.SubTabDto 的 ID。当前没有设备，也没有用户当前首页的缓存/响应；没有证据可给出线上“精选”和“必备”的具体 ID、路由对应表。因此本轮未硬编码这些值，未使用“必备”文字猜测过滤。

## 已完成的两阶段实现

1. 在 ޟ 的 before 回调内，根据已核实的原生分类路由，在现有两份列表中成对保留分类。先提交 p.args 中的过滤结果，再尝试原生分类 Fragment 检查/补建。首页中的非分类项（包括其对应页面）一并退出绑定；不调用 getTitle 匹配文字，也不依赖 strip/pager 查找或模块创建 Fragment 成功。
2. 在 ޟ 的独立 after 回调隐藏 strip。此回调不检查过滤是否命中、Fragment 是否补建成功或是否成功选中。选中操作另设 after 回调，不能短路隐藏。

视图查找与模式/原始数据记录拆成独立回调。各回调都能独立建立共享状态，不依赖相同方法上 before hook 的注册执行顺序。每次缓存/在线重绑定重置 bound，只有非空页面列表才能启用索引零恢复约束。复制列表后操作，不修改原始响应 DTO；关闭时重放保存的 DTO/position/cached，包含 null DTO。

边界：若原列表没有可识别分类，或模型/页面数量不同，第一步不发布空列表或不成对的列表；后续仍尝试原生分类补建和独立隐藏。此时如果分类补建也失败，允许保留原列表以避免空页/越界，不能宣称已移除所有目标 Tab。

## 针对性静态验证

`python3 scripts/check-store-purification.py`：387 项通过。`git diff --check` 通过。未编译或运行故障注入测试。

| 场景 | 已检查的代码路径 |
|---|---|
| 分类工厂/Fragment 检查失败 | 发生在过滤参数提交之后，局部 catch，不回滚列表、不关闭模式 |
| 顶栏查找失败 | 独立 before 回调捕获；数据过滤不读取 state.tabs/state.pager |
| 过滤未命中/列表不成对 | 不产生空列表；独立 after 隐藏仍执行 |
| 选中失败 | 独立 after 回调；不阻止隐藏 |
| 重复绑定/在线替换缓存 | 每次基于原始输入重新复制过滤，无追加或累计删除 |
| 关闭与缓存恢复 | 保存原始输入，关闭重放；非空列表才固定索引零 |
| 空白搜索首页及其它主导航 | 未修改搜索代码与主导航过滤；仅处理 HomeSubTabPresenter 的子列表 |
| 纵向滚动 | 沿用只标记该 presenter 外层 CdoViewPager 的触摸限制，不拦截分类内部滚动 |

## 待核实及设备验收

只读 adb devices -l 返回无设备，未进行任何设备界面操作。实际线上“精选/必备”模型 ID/路由尚未取得，无法完成用户要求的逐项真实标识核实；两阶段实现已经保留在工作区，不能将静态通过称为实机验收通过。

主会话构建后需核实：顶部实际数据仅分类、隐藏 strip、冷启动和缓存转在线不恢复被移除项、返回/状态恢复索引正确、关闭恢复原导航；分别组合空白搜索首页与隐藏原首页/软件/游戏/榜单，并检查纵向滚动和原生搜索下载更新。若进一步定位目标 Tab，需读取用户当前首页响应或缓存中的 id/title/actionParam，不能使用另一套主导航 ID 替代。
