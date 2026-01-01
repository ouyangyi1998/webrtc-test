package com.example.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

class ControlHandler {
    private final Robot robot;
    private final AgentClient.StatusListener listener;
    private final Dimension screen;

    ControlHandler(AgentClient.StatusListener listener) throws AWTException {
        this.listener = listener;
        this.robot = new Robot();
        this.screen = Toolkit.getDefaultToolkit().getScreenSize();
    }

    void handleMouse(JsonNode node) {
        double xRatio = node.path("xRatio").asDouble(0);
        double yRatio = node.path("yRatio").asDouble(0);
        int x = (int) Math.round(xRatio * screen.width);
        int y = (int) Math.round(yRatio * screen.height);
        String action = node.path("action").asText("");
        if ("move".equals(action)) {
            robot.mouseMove(x, y);
        } else if ("click".equals(action)) {
            robot.mouseMove(x, y);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        } else if ("wheel".equals(action)) {
            int deltaY = (int) Math.round(node.path("deltaY").asDouble(0) / 40.0); // scale down
            robot.mouseWheel(deltaY);
        }
        listener.onStatus(String.format("Mouse %s (%.3f,%.3f) -> %d,%d", action, xRatio, yRatio, x, y));
    }

    void handleKeyboard(JsonNode node) {
        String type = node.path("type").asText("");
        String key = node.path("key").asText("");
        int code = KeyEvent.getExtendedKeyCodeForChar(key.length() > 0 ? key.charAt(0) : 0);
        if (code == KeyEvent.VK_UNDEFINED) {
            listener.onStatus("Skip unsupported key: " + key);
            return;
        }
        if ("keydown".equals(type)) {
            robot.keyPress(code);
        } else if ("keyup".equals(type)) {
            robot.keyRelease(code);
        }
        listener.onStatus("Key " + type + ": " + key);
    }
}
