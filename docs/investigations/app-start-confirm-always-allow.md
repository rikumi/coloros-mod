# 恢复始终允许应用间跳转对话框

## 核对对象

- 用户提供的国行 `com.oplus.securitypermission` 16.08.003（1608003）。
- SHA256：`a07868b7dbbfb3af9bea64f3488f8a3fe847ac0aec639f0ac0f51c01b0bd9c22`。
- 使用 `../android-app-mods/_tools/apktool.jar`、JADX 及 Android SDK `dexdump` 核对。
- 本机参考文件位于 `/private/tmp/coloros-cn-reference-20261017/`；Java 反编译结果位于 `java/sources/`，逐指令结果位于 `classes2.dexdump.txt`。

## 原生调用路径及实施范围

1. `../android-app-mods/_framework/src-oplus-services/sources/com/android/server/wm/OplusAppStartConfirmManager.java` 的 `getCheckConformIntent()` 使用 `oplus.app.action.CHECK_ALLOW_START_ACTIVITY`，指定包 `com.oplus.securitypermission`，传入 `start_type`、`activity_start_confirm_version=2`、调用方、目标、UID、用户及原始 Intent。
2. 国行 APK Manifest 将该 action 注册到 `com.oplusos.securitypermission.permission.ui.AppStartConfirmDialogActivity`，进程为 `:ui`。Activity 在 `onCreate(Bundle)` 后的协程中读取版本及跳转信息，生成 `i9.b`，再按 `i9.a` 分派原生对话框。
3. `i9.k#a(i9.b, Activity)` 在版本为 2 时调用 `l()`，否则调用 `r()`。`l()` 的普通跳转按钮为“30天内允许”；`r()` 使用原生 `f2.f` 构建带“始终允许打开”复选框的对话框。`f2.f` 的勾选状态字段在构造时保持 Java 默认值 false，`u0()` 将其传给复选框 `setChecked()`，所以初始未勾选；`F0(false)` 控制的是另一段声明文字，不是勾选状态。
4. `i9.k#k(Activity, int, boolean, i9.b)` 只有在点击允许（`which=-1`）且已勾选时，调用 `i9.k$a#g()` → `f(caller, callee, null)`。取消、返回和未勾选均不创建长期授权。
5. `f()` 生成 `src_and_dst`，仅在有效期非 null 时写 `valid_time`，经 `h9.c#d(Bundle)` 调用原生 Binder `putActivityStartWhiteList`。系统 `OplusActivityStartController` 按用户和应用关系保存，随后原生 `checkBlackAndWhiteList()` 将无有效期记录识别为 `ALWAYS_ALLOW_START=101`。模块不调用或拦截这些保存及放行方法。

实现仅在用户开启开关、action 正确、版本为 2 且 `start_type` 为 0、5、6 时，为该 Activity 复制 Intent 并选择原生版本 0。支付保护 1/2、小程序拦截 3/4、黑名单 7、游戏支付拦截 8 及未知类型保持原路径。尤其不能将黑名单类型 7 改为旧版：虽然它也由 `i9.k` 创建，旧版 `r()` 不保留新版黑名单分支的单次允许限制。

开关默认关闭。关闭后新建的确认框直接沿原生版本与策略运行；已打开的对话框保持其创建时的样式。此前由用户明确勾选保存的授权继续由系统管理，关闭模块开关不撤销这些授权。

## 静态及针对性验证

- `dexdump` 确认 Activity 的 `onCreate(Bundle)` 签名，以及 `i9.k` 的版本分支、默认未勾选和确认后保存调用；JADX 对其它类报告错误，相关调用以 DEX 指令交叉核对。
- `python3 scripts/check-app-start-confirm.py` 使用生产 hook 源码配合 JVM 桩，覆盖默认关闭、开关切换、版本和风险类型边界、Intent 副本及全部其它跳转参数保留，并检查四语言资源和作用域接入。
- 不构建 APK，不安装或重载目标进程。本次验证未覆盖设备界面、Binder 实际写入及真实跨应用跳转。

## 用户验收

1. 在 LSPosed 模块作用域中包含“权限管理 / com.oplus.securitypermission”，使用尚无原生授权的应用关系触发首次跳转；关闭开关时应维持原生对话框。
2. 开启“系统净化 → 权限管理 → 恢复始终允许应用间跳转对话框”，重新触发跳转，应出现原生“始终允许打开”复选框且默认未选中；取消不能启动或产生授权。
3. 不勾选并点击允许，只允许本次；换一组未授权关系，勾选后点击允许，后续相同调用方至相同目标的普通跳转应由原生授权直接处理，其它关系仍需确认。
4. 关闭开关，用另一组尚无授权的关系检查原生对话框及原生期限。已保存授权不应因关闭开关而被删除。
5. 支付保护、小程序和黑名单等特殊跳转维持原生提示，不出现新增长期授权能力；检查应用分身/多用户时，授权不能跨用户混用。
