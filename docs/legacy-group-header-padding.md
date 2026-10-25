# 旧版分组通知几何与边距

分组上下内边距统一由 NotificationGroupPaddingHooks 计算。旧版普通分组在 onLayout 后将真实头部 top 设为 inset，动画状态保留原生 mCurrentHeaderTranslation，避免重复施加偏移。内容尺寸与展开插值由 LegacyGroupGeometry 统一计算。

折叠头部高 46dp，展开头部高 56dp；折叠内容基础起点为 40dp，展开内容基础起点为 56dp，再按布局状态应用边距。子通知的测量高度包含其上下内边距，避免正文与头部重叠或被裁切。

展开头部使用独立应用名称，不增加背景；分组卡片与子卡片随展开进度连续淡变。异步头部背景更新只在旧版普通分组内拦截，其它模式保留原生行为。

旧版操作按钮在原生和厂商布局更新后清除容器的起始 margin、padding，保留按钮点击区域和垂直间距。设置切换、旧布局清理和重新绑定均在主线程串行执行。

绘制期间只在实际颜色变化时设置图标 tint，背景已为空时跳过重复 setter，避免无变化的属性更新持续触发重绘。反射方法与资源名称查询分别采用类身份缓存和按 Resources 隔离的缓存，配置相关尺寸仍实时读取。

`tests/legacy-notifications/LegacyGroupGeometryTest.java` 覆盖折叠、展开及中间进度的高度和位置关系。
