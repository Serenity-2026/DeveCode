package com.agent.tui;

/**
 * 输入线程 → 主线程的事件（密封接口）。
 *
 * 事件类型：
 *   - KeyTyped：可打印字符（ch >= 32 或 Tab），由主线程 handleKeyTyped 处理
 *   - Submit：用户按 Enter 提交的文本，由主线程 submitMessage 处理
 *   - PickerConfirm：全屏选择器按 Enter 确认
 *   - TerminalResize：终端尺寸变化
 *   - Exit：退出请求（Ctrl+C 或 EOF）
 */
sealed interface UIEvent {
    record KeyTyped(int ch) implements UIEvent {}
    record Submit(String text) implements UIEvent {}
    record PickerConfirm() implements UIEvent {}
    record TerminalResize(int cols, int rows) implements UIEvent {}
    record Exit() implements UIEvent {}
}
