package com.blissfuljuan.dieapi.ops.controller;

import com.blissfuljuan.dieapi.ops.service.PlatformHealthService;
import com.blissfuljuan.dieapi.ops.view.PlatformHealthView;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Per-dependency health for administrators (database, broker, workers, backlog). Public probes live under /actuator. */
@RestController
@RequestMapping("/api/v1/ops/health")
@PreAuthorize("hasRole('ADMIN')")
public class PlatformHealthController {

    private final PlatformHealthService health;

    public PlatformHealthController(PlatformHealthService health) {
        this.health = health;
    }

    @GetMapping
    public ResponseEntity<PlatformHealthView> health() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(health.report());
    }
}
