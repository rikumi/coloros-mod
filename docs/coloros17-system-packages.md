# ColorOS 17 新增系统包核对

采集时间：2026-11-02（Asia/Shanghai）。设备：PKT110，固件：PKT110_17.0.0.102(CN01)。

与同机 ColorOS 16 基线 `device-state/oppo-pkt110/versions/v0001.json` 对照，`pm list packages -s -u -f` 新增 28 项，24 项原先缺少描述。本结论限于该固件与基线，不代表所有机型或所有 ColorOS 17 版本。

核对依据：当前包路径、APK SHA256、AndroidManifest.xml 组件/动作/overlay 目标，以及网络插件 Loader、AISeal Provider 的反编译代码。卸载标记为静态依赖评估，未执行卸载或验证移除后的运行情况；可卸载表示可以按功能取舍，不表示已经验证安全，也不保证包管理器接受卸载。

网络插件的 `Loaded:false` 是清单元数据，不代表现场未加载。`com.oplus.nas` 是 NetworkAssistSys，已纠正旧有个人云盘描述。凭据包明确包含 FIDO2 创建、获取、远端 Passkey 及二维码认证入口，按保护通行密钥的既有要求保留。

| 包名 | 标记 | 功能 / 说明 | APK SHA256（本轮提取） |
| --- | --- | --- | --- |
| `com.android.appsearch.aiseal.config` | 不建议卸载 | AppSearch 的 AISeal 资源配置；与隔离数据服务配套保留 | a3390122f75a8bf0addb8ad134672dbad257f8c479c84ad668ef28666e9d082e |
| `com.android.contactspicker` | 不建议卸载 | 系统联系人选择器、按选择结果授权联系人访问；卸载可能导致应用选择联系人失败 | fe3c72ba6156dffffa0b1be4735060271c35f2f2178db14c21548e3aeda8f6ca |
| `com.android.eyedropper` | 可卸载 | 系统屏幕取色器；卸载后无法使用取色功能 | 889f70b003b1b1f5f7ac582833a73b2d98b76436d64a7314949e2f99d6e21a84 |
| `com.android.privatespace` | 不建议卸载 | Android 私密空间组件；移除可能影响隐藏资料空间及其数据操作 | 53ccdb7a2f8ffee509d4633068d48c50bff4aa679c60e5201c02a37816ac5d65 |
| `com.android.systemui.overlay.fingerprint.anim.jijian.icon` | 可卸载 | 指纹图标资源；移除后失去对应样式 | 9f731c0a0c9c16d331dc4bf9e0b73e982969090b9a2955c7dc609e8adb183382 |
| `com.android.systemui.overlay.fingerprint.anim.melodicstream.icon` | 可卸载 | 指纹图标资源（沿用已有描述） | 本轮未提取（已有描述） |
| `com.android.systemui.overlay.fingerprint.anim.melodicstream.press` | 可卸载 | 指纹按压资源（沿用已有描述） | 本轮未提取（已有描述） |
| `com.android.webapp.service` | 可卸载 | 网页应用安装、运行及通知服务；卸载后相关网页应用功能不可用 | 7d019e403370f4f83e28e7f590b2a2ea50889596c9d073528f2e7f24ab2c322f |
| `com.coloros.baseocrscanner` | 可卸载 | AI 扫描基础界面与相机扫描入口；卸载后相关扫描入口不可用 | b565e19fb5d7dd4fc45731c235c24ea90dd2dfcd595f3ed3e90c3deec221a6ec |
| `com.coloros.gamesettings` | 可卸载 | 游戏画质、帧率、操控及游戏配件设置；卸载后相关设置不可用 | 112a4d744213aa5c5ddfddc803a673481f1ef7e2bc9f1c09b25fb9c16728b8b6 |
| `com.ct.ertclib.dc.overlay` | 不建议卸载 | 电信新通话数据通道资源配置；卸载可能影响增强通话 | 5ded5b0380799c1beee889a63daab25abba566f9383670704c83a3165e43c344 |
| `com.globaldrag.dragplugin` | 可卸载 | 跨应用拖放插件（沿用已有描述） | 本轮未提取（已有描述） |
| `com.google.android.gms.policy_sidecar_aps` | 不建议卸载 | Google Play 服务策略配套组件（沿用已有描述） | 本轮未提取（已有描述） |
| `com.juphoon.cloud.sdkoverlay` | 不建议卸载 | 联通新通话 SDK 资源配置；卸载可能影响增强通话 | 33e9f0ace5a771d92c7de9c7ef4c1e02656fe3c651b6943269ec5e10ade3ecb5 |
| `com.mediatek.telecom.res.overlay` | 不建议卸载 | 联发科 Telecom 通话资源配置；不建议移除通话适配 | 29ef2b47d1766f966dd8f7fe095db22a301685c7ed2bb58b0cab7116e2720bd4 |
| `com.nearme.instant.card` | 可卸载 | 快应用卡片引擎、卡片安装及负一屏集成；卸载后相关卡片不可用 | c69ebfe382ccb10a23b45e6e34c25238495a5b97e9768ad3e1d3b89c87c04e84 |
| `com.oplus.aiseal` | 不建议卸载 | AISeal 隔离数据库与文件访问服务；移除可能影响依赖它的 AI 数据功能 | 9633657cde183badfeaad21dfb358e475de7f79542f66095dc83cb4294097a02 |
| `com.oplus.credential` | 不建议卸载 | FIDO2 通行密钥创建、登录及跨设备二维码认证；卸载可能破坏已有凭据的使用 | 8571e54967efebf5439b6fc02fdddae8489fba9cd3ab6fb747b3e0d917ef6d88 |
| `com.oplus.devicehub` | 可卸载 | 设备发现、第三方设备连接及跨设备文件服务；卸载后相关互联功能不可用 | f3f52a491b0df92536c48be85f064b4d14c1c40f48a4c747aa74bc17e6675dea |
| `com.oplus.nas.acc` | 不建议卸载 | 蜂窝网络加速策略插件；由网络辅助系统加载，移除可能影响联网策略 | c0cb46257fb0ae1bd4067a1e475d720a4d0f239602a0b15aaa6bd51d4037a98f |
| `com.oplus.nas.data` | 不建议卸载 | 蜂窝数据网络策略插件；由网络辅助系统加载，移除可能影响联网策略 | 1ea4703314223469719c2bce9320f91a9e5988e7908ab6ad860cef91df5fcd43 |
| `com.oplus.nas.dds` | 不建议卸载 | 双卡默认数据网络切换插件；由网络辅助系统加载，移除可能影响联网策略 | 0bdbae0efda9095f29c54cda41384cdb442de1ed013de6fc84019c11d0c2a724 |
| `com.oplus.nas.network` | 不建议卸载 | 蜂窝网络与功耗策略插件；由网络辅助系统加载，移除可能影响联网策略 | f4d136d13037dbda60e42768e03796e16b3aa51716b2b9b304351dcdb2b8f186 |
| `com.oplus.nas.recovery` | 不建议卸载 | 蜂窝注册与网络恢复插件；由网络辅助系统加载，移除可能影响联网策略 | a4b4af94b99d1b225fa65ba7149b75c1759d1d7e82e5d544704badc87b57ac0d |
| `com.oplus.nas.smartcommu` | 不建议卸载 | 蜂窝通信质量优化插件；由网络辅助系统加载，移除可能影响联网策略 | 7d28690bcfdf637ff01c33cd33e3cbbf5194d25f145bcf6deaa51e5456967beb |
| `com.oplus.nas.virtualdata` | 不建议卸载 | 虚拟数据连接协同插件；由网络辅助系统加载，移除可能影响联网策略 | d658e2d140fcfe0ea1a0115e3dabfe92d1169cd3ce1a27c24317031f58e9a5bc |
| `com.oplus.nas.voice` | 不建议卸载 | 蜂窝通话网络协同插件；由网络辅助系统加载，移除可能影响联网策略 | 810712e8d6aad65ad60f53e1b6543db1a4959920c9365a55d9f45e3593fe1c89 |
| `com.oplus.ohome` | 可卸载 | 智能家居设备添加、厂商授权及设备控制；卸载后相关家居功能不可用 | d9b0e03efaff9cfd49e98617c35ea8502aaf7a381db986d330d7b532d059a0de |
