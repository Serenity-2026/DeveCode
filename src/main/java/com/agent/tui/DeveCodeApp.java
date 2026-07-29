package com.agent.tui;

import com.agent.ProviderConfig;
import java.io.IOException;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp.Capability;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import static org.jline.keymap.KeyMap.key;
import static org.jline.keymap.KeyMap.ctrl;

import java.io.PrintWriter;
import java.util.List;

/**
 * 应用入口：加载配置 → provider 选择 → 启动终端 UI。
 *
 * provider 选择：
 *   单 provider    → 直接进入对话
 *   多 provider    → 方向键选择界面
 */
public class DeveCodeApp {

    private static final String ESC = "\033";
    private static final String RESET = ESC + "[0m";
    private static final String BOLD  = ESC + "[1m";
    private static final String CYAN  = ESC + "[36m";
    private static final String GRAY  = ESC + "[90m";

    public static void main(String[] args) {
        AppConfig config = AppConfig.load();
        List<ProviderConfig> providers = config.getProviders();

        ProviderConfig selected;
        if (providers.size() == 1) {
            // F2: 单 provider 直接进入
            selected = providers.get(0);
            System.out.println("Starting DeveCode with " + selected.getName() + "...");
        } else {
            // F2: 多 provider 显示选择界面
            selected = showProviderSelector(providers);
        }

        TerminalUI.launch(selected);
    }

    /**
     * ++++++ provider +++++
     *
     * ^v ++++ / Enter ++ / Ctrl+C +++
     * ++ JLine ++ raw-mode+++++++++
     */
    private static ProviderConfig showProviderSelector(List<ProviderConfig> providers) {
        try {
            Terminal terminal = TerminalBuilder.builder()
                    .jna(true).system(true)
                    .signalHandler(Terminal.SignalHandler.SIG_IGN)
                    .build();
            terminal.enterRawMode();
            PrintWriter writer = terminal.writer();

            int selectedIdx = 0;

            BindingReader bindingReader = new BindingReader(terminal.reader());
            KeyMap<String> keys = new KeyMap<>();
            keys.bind("up",    key(terminal, Capability.key_up),    "\033[A", "\033OA");
            keys.bind("down",  key(terminal, Capability.key_down),  "\033[B", "\033OB");
            keys.bind("enter", "\r", "\n");
            keys.bind("quit",  ctrl('C'));

            while (true) {
                Integer hObj = terminal.getHeight();
                Integer wObj = terminal.getWidth();
                int h = (hObj != null && hObj > 0) ? hObj : 24;
                int w = (wObj != null && wObj > 0) ? wObj : 80;

                StringBuilder buf = new StringBuilder();
                buf.append(ESC + "[2J" + ESC + "[3J" + ESC + "[H" + ESC + "[?25l"); // ++++++

                // +++
                int boxW = Math.min(60, w - 4);
                int boxH = providers.size() + 6;
                int boxX = (w - boxW) / 2;
                int boxY = (h - boxH) / 2;

                // +++
                buf.append(ESC + "[").append(boxY + 1).append(";").append(boxX + 1).append("H");
                buf.append(GRAY + '\u256d' + repeat('\u2500', boxW - 2) + '\u256e' + RESET);

                // ++
                String title = "Select Provider";
                int titleX = boxX + (boxW - title.length()) / 2;
                buf.append(ESC + "[").append(boxY + 2).append(";").append(titleX + 1).append("H");
                buf.append(BOLD + title + RESET);

                // provider ++
                for (int i = 0; i < providers.size(); i++) {
                    ProviderConfig p = providers.get(i);
                    String prefix = (i == selectedIdx) ? BOLD + " > " : "   ";
                    String line = String.format("%s %s  %s  %s",
                            prefix,
                            CYAN + p.getName() + RESET,
                            GRAY + p.getProtocol() + RESET,
                            GRAY + p.getModel() + RESET);

                    int lineX = boxX + 2;
                    buf.append(ESC + "[").append(boxY + 4 + i).append(";").append(lineX + 1).append("H");
                    buf.append(line);
                }

                // ++
                int hintY = boxY + 4 + providers.size();
                String hint = GRAY + "Use \u2191\u2193 to navigate, Enter to select" + RESET;
                int hintX = boxX + (boxW - hint.replaceAll(ESC + "\\[[0-9;]*[a-zA-Z]", "").length()) / 2;
                buf.append(ESC + "[").append(hintY + 1).append(";").append(hintX + 1).append("H");
                buf.append(hint);

                // +++
                buf.append(ESC + "[").append(boxY + boxH).append(";").append(boxX + 1).append("H");
                buf.append(GRAY + '\u2570' + repeat('\u2500', boxW - 2) + '\u256f' + RESET);

                writer.print(buf.toString());
                writer.flush();

                String op = bindingReader.readBinding(keys);
                if (op == null || op.equals("quit")) { System.exit(0); }

                if (op.equals("up") && selectedIdx > 0) {
                    selectedIdx--;
                } else if (op.equals("down") && selectedIdx < providers.size() - 1) {
                    selectedIdx++;
                } else if (op.equals("enter")) {
                    break;
                }
            }


            terminal.close();

            // ++++++
            ProviderConfig chosen = providers.get(selectedIdx);
            System.out.println("Selected: " + chosen.getName() + " (" + chosen.getModel() + ")");
            return chosen;

        } catch (Exception e) {
            System.err.println("Provider selection failed: " + e.getMessage());
            System.exit(1);
            return null;
        }
    }

    private static String repeat(char c, int n) {
        return n > 0 ? String.valueOf(c).repeat(n) : "";
    }
}
