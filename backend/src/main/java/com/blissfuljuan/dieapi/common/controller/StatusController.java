package com.blissfuljuan.dieapi.common.controller;

import com.blissfuljuan.dieapi.common.view.StatusView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;

/** Versioned application status for the frontend's connectivity check. Dependency health is P0-VS05. */
@RestController
@RequestMapping("/api/v1")
public class StatusController {

    private final Clock clock = Clock.systemUTC();

    @GetMapping("/status")
    public StatusView status() {
        String build = StatusController.class.getPackage().getImplementationVersion();
        return new StatusView("die-api", "v1", build == null ? "dev" : build, "UP", Instant.now(clock));
    }
}
