# 系统字体与热重载

2026-10-24 只读检查 OPPO PKT110 的 LSPosed 2.2.1 (7912) 日志及模块源码。

## 批量拒绝

`onHotReloading` 在 `NativeFontHooks.isActive()` 时直接返回 false。选择系统字体后，原生桥在大量作用域中激活，使 SystemUI、桌面、设置、软件商店及 Chrome 等均拒绝 Java hooks 的重载。14:54:35 的框架日志明确记录模块类拒绝，回执为 status=1、message=null。

修正为保留最初加载原生库的 Class，并通过已有的 Object[] saved state 在代际之间交接。新代只持有最初的原生库所有者，不重新安装 libc hooks，不建立逐代引用链。这个引用有意存活至进程退出，防止 ART 卸载仍被 libc 回调使用的库。C++ 原子快照和字体描述符保持原状；字体配置与原生代码更新仍需进程重启。

只使用字体的作用域不创建功能设置 worker，不等待应用主线程进行不存在的 UI 清理；热重载仍重新安装通用等宽字体保护。功能作用域保留原有的 quiesce、worker barrier 和主线程清理。

已运行旧版本的进程仍会先执行旧版拒绝逻辑，因此第一次采用此修复需要用户重启对应进程。安装新 APK 不能越过旧代的拒绝。本次没有重启进程、设备或替换 LSPosed。

## 进度延迟的框架边界

设备日志还存在 status=3（先前请求进行中），以及 Chrome 在 14:54 后至 15:05 才返回模块拒绝的记录。当前 WebView 服务的 ActivityManager 状态是 `isFrozen=true`。这与后台进程冻结后无法及时执行重载 Binder 回调的情况一致，但当前状态不能证明此前每一次延迟的原因。

已反编译的 LSPosed daemon `j8.b` 使用 oneway Binder 向目标进程发送请求，保留单个 in-flight 请求直到回执或进程死亡；目标再次请求时返回 IN_PROGRESS。安装更新后的自动进度通知由 LSPosed daemon 管理。模块无法在被冻结期间执行超时回调，也不能从目标进程中为 daemon 的通知补回执。本次未将这些框架等待伪装成重载成功，也未关闭系统冻结策略。

`MainActivity` 菜单手动重载具有 30 秒批次超时；它与 LSPosed 安装 APK 后的自动通知是两条不同路径。

## 验证

`python3 scripts/check-native-font-reload.py` 编译实际 NativeFontHooks，并在八个相互隔离的 ClassLoader 代际中检查不活动状态、非法所有者拒绝、原始库所有者恒定及不重复调用原生初始化，共 34 项检查。Android 构建与这些检查不能代替设备上首次进程重启后的连续热重载验证。

## 2026-10-25：迟到截图与设置恢复

再次读取 OPPO PKT110：07:10:09 SystemUI/31575 返回拒绝；07:10:07 框架记录向冻结进程发送单向调用，Chrome 子进程在 07:25:27 死亡后才返回 PROCESS_DIED。未保留到对应 SystemUI 模块回调日志，因此不能将该次拒绝直接归因于某一个 preflight。

源码检查发现导航栏截图超时会放弃当前 listener，但 captures 计数只在原生结果处理完成后减少。原生回调延迟或不再到达时，后续每次热重载都会因等待该计数而失败。新增 NavigationCaptureCallback，在 preflight 中先关闭截图准入，再安全作废所有待返回结果，随后保留 worker/captureWorker 的队列屏障。已入队结果仍由屏障等待；迟到结果只关闭 HardwareBuffer，不再引用 View、effect 或 worker。不等待 GPU 回调，不将仍在执行的应用任务查询或提交当作已经结束。

已成功读取的完整设置快照也随代际交接，仅复制 String/Integer 数据。新代先用此快照恢复 hooks，再后台刷新，避免恢复期间逐项等待或同步查询繁忙的设置 Provider。旧代尚未取得完整快照时仍保留原有初始化路径。

`NavigationCaptureCallbackTest` 覆盖迟到释放、回调所有者释放、队列拒绝、入队异常，以及 100 轮取消与入队竞态，共 505 项检查。设备上的连续自动热重载仍待验证。冻结进程的唤醒和 LSPosed 自动通知批次期限仍由框架决定；本修改没有更改系统冻结策略、重启进程或将失败伪报为成功。
