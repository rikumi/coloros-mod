# 旧版消息通知间距

框架 MessagingGroup.setIsInConversation(false) 会使用 mNonConversationPaddingStart 和 mNonConversationContentStart 重设头像容器。旧版布局在该方法结束时，同时从容器宽度和起始 padding 减去额外额度，保留头像及原有尾部间隔，不修改消息内容。

切换回会话几何时放弃旧恢复记录；关闭旧版或热重载时还原记录的参数。处理仅在旧版模式生效。

厂商消息模板完成内容更新后，通知头部的末端边距与正文容器保持一致；无法取得正文末端边距时使用 16dp。该规则不改变展开按钮的原生点击区域。
