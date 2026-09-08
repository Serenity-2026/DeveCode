package com.agent.tui;

import java.util.List;

/** 选择器条目：id（会话 ID / 序号）、title（主标题）、subtitle（元信息行）、payload（原始数据）。 */
record PickerItem(String id, String title, String subtitle, Object payload) {}

/** 选择器状态：kind（"session" / "snapshot"，决定确认后的动作）、标题、条目列表。 */
record PickerState(String kind, String title, List<PickerItem> items) {}
