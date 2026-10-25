# AOD 监听器替换兼容

AodUpdateReceiver 在遍历监听器列表时移除旧控制器，可能使后续索引越界。兼容处理在原生 addListener 前筛选旧控制器，通过原生 removeListener 移除，再完整执行注册流程。

添加和移除使用同一 receiver 锁，保持 AbstractReceiver 的集合与子类列表同步，保留其它监听器和广播生命周期。不会通过吞掉注册异常跳过新控制器。

独立回归程序位于 `tests/aod-receiver/ReceiverListenerReplacementTest.java`，覆盖不同排列、多个旧控制器、子类、重复启动及停止注销。
