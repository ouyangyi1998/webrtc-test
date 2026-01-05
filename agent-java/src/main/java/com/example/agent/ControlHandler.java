package com.example.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;

public class ControlHandler {
    private final Robot robot;
    private final AgentClient.StatusListener listener;
    private final Dimension screen;
    private final java.awt.datatransfer.Clipboard systemClipboard;

    // JavaScript key.code 到 Java KeyEvent.VK_* 的映射
    private static final Map<String, Integer> KEY_CODE_MAP = new HashMap<>();

    static {
        // 功能键
        KEY_CODE_MAP.put("F1", KeyEvent.VK_F1);
        KEY_CODE_MAP.put("F2", KeyEvent.VK_F2);
        KEY_CODE_MAP.put("F3", KeyEvent.VK_F3);
        KEY_CODE_MAP.put("F4", KeyEvent.VK_F4);
        KEY_CODE_MAP.put("F5", KeyEvent.VK_F5);
        KEY_CODE_MAP.put("F6", KeyEvent.VK_F6);
        KEY_CODE_MAP.put("F7", KeyEvent.VK_F7);
        KEY_CODE_MAP.put("F8", KeyEvent.VK_F8);
        KEY_CODE_MAP.put("F9", KeyEvent.VK_F9);
        KEY_CODE_MAP.put("F10", KeyEvent.VK_F10);
        KEY_CODE_MAP.put("F11", KeyEvent.VK_F11);
        KEY_CODE_MAP.put("F12", KeyEvent.VK_F12);

        // 方向键
        KEY_CODE_MAP.put("ArrowUp", KeyEvent.VK_UP);
        KEY_CODE_MAP.put("ArrowDown", KeyEvent.VK_DOWN);
        KEY_CODE_MAP.put("ArrowLeft", KeyEvent.VK_LEFT);
        KEY_CODE_MAP.put("ArrowRight", KeyEvent.VK_RIGHT);

        // 控制键
        KEY_CODE_MAP.put("Enter", KeyEvent.VK_ENTER);
        KEY_CODE_MAP.put("Backspace", KeyEvent.VK_BACK_SPACE);
        KEY_CODE_MAP.put("Tab", KeyEvent.VK_TAB);
        KEY_CODE_MAP.put("Escape", KeyEvent.VK_ESCAPE);
        KEY_CODE_MAP.put("Space", KeyEvent.VK_SPACE);
        KEY_CODE_MAP.put("Delete", KeyEvent.VK_DELETE);
        KEY_CODE_MAP.put("Insert", KeyEvent.VK_INSERT);
        KEY_CODE_MAP.put("Home", KeyEvent.VK_HOME);
        KEY_CODE_MAP.put("End", KeyEvent.VK_END);
        KEY_CODE_MAP.put("PageUp", KeyEvent.VK_PAGE_UP);
        KEY_CODE_MAP.put("PageDown", KeyEvent.VK_PAGE_DOWN);

        // 修饰键
        KEY_CODE_MAP.put("ShiftLeft", KeyEvent.VK_SHIFT);
        KEY_CODE_MAP.put("ShiftRight", KeyEvent.VK_SHIFT);
        KEY_CODE_MAP.put("ControlLeft", KeyEvent.VK_CONTROL);
        KEY_CODE_MAP.put("ControlRight", KeyEvent.VK_CONTROL);
        KEY_CODE_MAP.put("AltLeft", KeyEvent.VK_ALT);
        KEY_CODE_MAP.put("AltRight", KeyEvent.VK_ALT);
        KEY_CODE_MAP.put("MetaLeft", KeyEvent.VK_META);
        KEY_CODE_MAP.put("MetaRight", KeyEvent.VK_META);
        KEY_CODE_MAP.put("CapsLock", KeyEvent.VK_CAPS_LOCK);

        // 数字键盘
        KEY_CODE_MAP.put("Numpad0", KeyEvent.VK_NUMPAD0);
        KEY_CODE_MAP.put("Numpad1", KeyEvent.VK_NUMPAD1);
        KEY_CODE_MAP.put("Numpad2", KeyEvent.VK_NUMPAD2);
        KEY_CODE_MAP.put("Numpad3", KeyEvent.VK_NUMPAD3);
        KEY_CODE_MAP.put("Numpad4", KeyEvent.VK_NUMPAD4);
        KEY_CODE_MAP.put("Numpad5", KeyEvent.VK_NUMPAD5);
        KEY_CODE_MAP.put("Numpad6", KeyEvent.VK_NUMPAD6);
        KEY_CODE_MAP.put("Numpad7", KeyEvent.VK_NUMPAD7);
        KEY_CODE_MAP.put("Numpad8", KeyEvent.VK_NUMPAD8);
        KEY_CODE_MAP.put("Numpad9", KeyEvent.VK_NUMPAD9);
        KEY_CODE_MAP.put("NumpadMultiply", KeyEvent.VK_MULTIPLY);
        KEY_CODE_MAP.put("NumpadAdd", KeyEvent.VK_ADD);
        KEY_CODE_MAP.put("NumpadSubtract", KeyEvent.VK_SUBTRACT);
        KEY_CODE_MAP.put("NumpadDecimal", KeyEvent.VK_DECIMAL);
        KEY_CODE_MAP.put("NumpadDivide", KeyEvent.VK_DIVIDE);
        KEY_CODE_MAP.put("NumpadEnter", KeyEvent.VK_ENTER);
        KEY_CODE_MAP.put("NumLock", KeyEvent.VK_NUM_LOCK);

        // 符号键
        KEY_CODE_MAP.put("Minus", KeyEvent.VK_MINUS);
        KEY_CODE_MAP.put("Equal", KeyEvent.VK_EQUALS);
        KEY_CODE_MAP.put("BracketLeft", KeyEvent.VK_OPEN_BRACKET);
        KEY_CODE_MAP.put("BracketRight", KeyEvent.VK_CLOSE_BRACKET);
        KEY_CODE_MAP.put("Backslash", KeyEvent.VK_BACK_SLASH);
        KEY_CODE_MAP.put("Semicolon", KeyEvent.VK_SEMICOLON);
        KEY_CODE_MAP.put("Quote", KeyEvent.VK_QUOTE);
        KEY_CODE_MAP.put("Backquote", KeyEvent.VK_BACK_QUOTE);
        KEY_CODE_MAP.put("Comma", KeyEvent.VK_COMMA);
        KEY_CODE_MAP.put("Period", KeyEvent.VK_PERIOD);
        KEY_CODE_MAP.put("Slash", KeyEvent.VK_SLASH);

        // 字母键 (KeyA - KeyZ)
        for (char c = 'A'; c <= 'Z'; c++) {
            KEY_CODE_MAP.put("Key" + c, KeyEvent.VK_A + (c - 'A'));
        }

        // 数字键 (Digit0 - Digit9)
        for (int i = 0; i <= 9; i++) {
            KEY_CODE_MAP.put("Digit" + i, KeyEvent.VK_0 + i);
        }
    }

    public ControlHandler(AgentClient.StatusListener listener) throws AWTException {
        this.listener = listener;
        this.robot = new Robot();
        this.screen = Toolkit.getDefaultToolkit().getScreenSize();
        this.systemClipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        // 设置较短的自动延迟以提高响应速度
        this.robot.setAutoDelay(1);
    }

    public void handleClipboard(JsonNode node) {
        try {
            String text = node.path("text").asText("");
            if (text != null && !text.isEmpty()) {
                java.awt.datatransfer.StringSelection selection = new java.awt.datatransfer.StringSelection(text);
                systemClipboard.setContents(selection, null);
                listener.onStatus("剪贴板已更新: " + (text.length() > 20 ? text.substring(0, 20) + "..." : text));
            }
        } catch (Exception e) {
            listener.onStatus("设置剪贴板失败: " + e.getMessage());
        }
    }

    public void handleMouse(JsonNode node) {
        try {
            double xRatio = node.path("xRatio").asDouble(0);
            double yRatio = node.path("yRatio").asDouble(0);
            int x = (int) Math.round(xRatio * screen.width);
            int y = (int) Math.round(yRatio * screen.height);
            String action = node.path("action").asText("");
            int button = node.path("button").asInt(0); // 0=左键, 1=中键, 2=右键

            switch (action) {
                case "move":
                    robot.mouseMove(x, y);
                    break;

                case "click":
                    robot.mouseMove(x, y);
                    int mask = getMouseButtonMask(button);
                    robot.mousePress(mask);
                    robot.mouseRelease(mask);
                    break;

                case "dblclick":
                    robot.mouseMove(x, y);
                    int dblMask = getMouseButtonMask(button);
                    // 双击
                    robot.mousePress(dblMask);
                    robot.mouseRelease(dblMask);
                    robot.mousePress(dblMask);
                    robot.mouseRelease(dblMask);
                    break;

                case "mousedown":
                    robot.mouseMove(x, y);
                    robot.mousePress(getMouseButtonMask(button));
                    break;

                case "mouseup":
                    robot.mouseMove(x, y);
                    robot.mouseRelease(getMouseButtonMask(button));
                    break;

                case "contextmenu":
                    // 右键菜单
                    robot.mouseMove(x, y);
                    robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
                    robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
                    break;

                case "wheel":
                    robot.mouseMove(x, y);
                    int deltaY = (int) Math.round(node.path("deltaY").asDouble(0) / 40.0);
                    if (deltaY != 0) {
                        robot.mouseWheel(deltaY);
                    }
                    break;

                default:
                    listener.onStatus("未知鼠标动作: " + action);
                    return;
            }

            // 只在非移动事件时记录日志，避免日志过多
            if (!"move".equals(action)) {
                listener.onStatus(String.format("Mouse %s btn=%d (%.3f,%.3f) -> %d,%d",
                        action, button, xRatio, yRatio, x, y));
            }
        } catch (Exception e) {
            listener.onStatus("鼠标操作失败: " + e.getMessage());
        }
    }

    /**
     * 获取鼠标按钮掩码
     */
    private int getMouseButtonMask(int button) {
        switch (button) {
            case 0:
                return InputEvent.BUTTON1_DOWN_MASK; // 左键
            case 1:
                return InputEvent.BUTTON2_DOWN_MASK; // 中键
            case 2:
                return InputEvent.BUTTON3_DOWN_MASK; // 右键
            default:
                return InputEvent.BUTTON1_DOWN_MASK;
        }
    }

    public void handleKeyboard(JsonNode node) {
        try {
            String type = node.path("type").asText("");
            String key = node.path("key").asText("");
            String code = node.path("code").asText("");

            // 尝试从 code 获取键码（更准确）
            Integer keyCode = KEY_CODE_MAP.get(code);

            // 如果 code 没有映射，尝试从 key 获取
            if (keyCode == null) {
                keyCode = getKeyCodeFromKey(key);
            }

            if (keyCode == null) {
                listener.onStatus("Skip unsupported key: " + key + " (code: " + code + ")");
                return;
            }

            if ("keydown".equals(type)) {
                robot.keyPress(keyCode);
            } else if ("keyup".equals(type)) {
                robot.keyRelease(keyCode);
            }

            listener.onStatus("Key " + type + ": " + key + " (code: " + code + ")");
        } catch (Exception e) {
            listener.onStatus("键盘操作失败: " + e.getMessage());
        }
    }

    /**
     * 从 key 值获取键码（用于单字符键）
     */
    private Integer getKeyCodeFromKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }

        // 特殊 key 值映射
        switch (key) {
            case "Enter":
                return KeyEvent.VK_ENTER;
            case "Backspace":
                return KeyEvent.VK_BACK_SPACE;
            case "Tab":
                return KeyEvent.VK_TAB;
            case "Escape":
                return KeyEvent.VK_ESCAPE;
            case " ":
                return KeyEvent.VK_SPACE;
            case "Delete":
                return KeyEvent.VK_DELETE;
            case "ArrowUp":
                return KeyEvent.VK_UP;
            case "ArrowDown":
                return KeyEvent.VK_DOWN;
            case "ArrowLeft":
                return KeyEvent.VK_LEFT;
            case "ArrowRight":
                return KeyEvent.VK_RIGHT;
            case "Home":
                return KeyEvent.VK_HOME;
            case "End":
                return KeyEvent.VK_END;
            case "PageUp":
                return KeyEvent.VK_PAGE_UP;
            case "PageDown":
                return KeyEvent.VK_PAGE_DOWN;
            case "Insert":
                return KeyEvent.VK_INSERT;
            case "Shift":
                return KeyEvent.VK_SHIFT;
            case "Control":
                return KeyEvent.VK_CONTROL;
            case "Alt":
                return KeyEvent.VK_ALT;
            case "Meta":
                return KeyEvent.VK_META;
            case "CapsLock":
                return KeyEvent.VK_CAPS_LOCK;
        }

        // F1-F12
        if (key.matches("F\\d+")) {
            int fNum = Integer.parseInt(key.substring(1));
            if (fNum >= 1 && fNum <= 12) {
                return KeyEvent.VK_F1 + fNum - 1;
            }
        }

        // 单字符
        if (key.length() == 1) {
            char c = key.charAt(0);

            // 字母
            if (c >= 'a' && c <= 'z') {
                return KeyEvent.VK_A + (c - 'a');
            }
            if (c >= 'A' && c <= 'Z') {
                return KeyEvent.VK_A + (c - 'A');
            }

            // 数字
            if (c >= '0' && c <= '9') {
                return KeyEvent.VK_0 + (c - '0');
            }

            // 常用符号
            switch (c) {
                case '-':
                    return KeyEvent.VK_MINUS;
                case '=':
                    return KeyEvent.VK_EQUALS;
                case '[':
                    return KeyEvent.VK_OPEN_BRACKET;
                case ']':
                    return KeyEvent.VK_CLOSE_BRACKET;
                case '\\':
                    return KeyEvent.VK_BACK_SLASH;
                case ';':
                    return KeyEvent.VK_SEMICOLON;
                case '\'':
                    return KeyEvent.VK_QUOTE;
                case '`':
                    return KeyEvent.VK_BACK_QUOTE;
                case ',':
                    return KeyEvent.VK_COMMA;
                case '.':
                    return KeyEvent.VK_PERIOD;
                case '/':
                    return KeyEvent.VK_SLASH;
            }
        }

        return null;
    }
}
