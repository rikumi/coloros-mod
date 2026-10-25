# 模块提供家居控制磁贴

设置位于控制中心，键为 module_home_controls_tile_enabled，默认关闭。一键开启仅在 persist.sys.oplus.region 为 CN，或属性为空且 OTA 标识为 CN01 时启用。无法确认地区时跳过，不覆盖已有手动开启。

开启后启用模块 TileService 组件，用户可在磁贴编辑面板添加“家居控制”。不主动启用原生磁贴；失去 root 后既有模块磁贴仍可保留。图标沿用 SystemUI 的设备控制图案。

正常点击通过 SystemUI 共享 ControlsComponent 处理。在 ControlsListingControllerImpl 的后台执行器刷新当前用户提供者并解析 panelActivity，再在主线程使用 ControlsUiController.resolveActivity 与原生 ActivityStarter 打开界面。

原生添加应用页会过滤已经授权的 panelActivity，直接打开该页可能出现空列表。因此正常路径先完成提供者刷新，再解析已选择的控制器；不修改系统授权和地区规则。

没有注入或原生启动失败时，使用普通应用入口优先打开 Google Home，其次米家。清单声明对应包查询，使用 PendingIntent 与平台收起面板接口，不额外申请控制服务权限。两个应用均不可用时显示提示。
