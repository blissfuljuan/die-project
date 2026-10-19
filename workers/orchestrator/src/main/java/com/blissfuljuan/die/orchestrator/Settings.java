package com.blissfuljuan.die.orchestrator;

import java.util.Map;

/** Worker settings from the environment (12-factor). No secret has a default. */
public record Settings(String host, int port, String username, String password, String virtualHost, int healthPort,
                       int prefetch) {

    public static Settings fromEnv(Map<String, String> env) {
        return new Settings(
            env.getOrDefault("RABBITMQ_HOST", "localhost"),
            Integer.parseInt(env.getOrDefault("RABBITMQ_PORT", "5672")),
            env.getOrDefault("RABBITMQ_USER", ""),
            env.getOrDefault("RABBITMQ_PASSWORD", ""),
            env.getOrDefault("RABBITMQ_VHOST", "/"),
            Integer.parseInt(env.getOrDefault("HEALTH_PORT", "8081")),
            Integer.parseInt(env.getOrDefault("DIE_PREFETCH", "5")));
    }
}
