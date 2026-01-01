package com.example.agent;

import com.example.agent.javafx.AgentAppFX;

/**
 * Launcher class to avoid JavaFX module issues when running from Fat JAR
 * 
 * This class doesn't extend Application, so it can be used as the main class
 * in the manifest without triggering JavaFX's module verification.
 */
public class Launcher {
    public static void main(String[] args) {
        AgentAppFX.main(args);
    }
}
