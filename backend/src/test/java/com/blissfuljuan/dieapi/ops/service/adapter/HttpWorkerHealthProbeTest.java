package com.blissfuljuan.dieapi.ops.service.adapter;

import com.blissfuljuan.dieapi.ops.model.WorkerHealth;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HttpWorkerHealthProbeTest {

    @Test
    void blankUrlsAreNotConfiguredAndAClosedPortIsUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        List<WorkerHealth> none = new HttpWorkerHealthProbe("", " ", new ObjectMapper()).probeAll();
        assertThat(none).extracting(WorkerHealth::state)
            .containsExactly(WorkerHealth.State.NOT_CONFIGURED, WorkerHealth.State.NOT_CONFIGURED);

        List<WorkerHealth> down = new HttpWorkerHealthProbe("http://127.0.0.1:" + closedPort + "/health/ready", "",
            new ObjectMapper()).probeAll();
        assertThat(down.get(0).state()).isEqualTo(WorkerHealth.State.UNREACHABLE);
        assertThat(down.get(0).heartbeatAgeSeconds()).isNull();
        assertThat(down.get(1).state()).isEqualTo(WorkerHealth.State.NOT_CONFIGURED);
    }

    @Test
    void hostAndPortAloneAreExpandedToTheReadinessUrl() {
        assertThat(HttpWorkerHealthProbe.normalize("die-orchestrator:10000")).isEqualTo("http://die-orchestrator:10000/health/ready");
        assertThat(HttpWorkerHealthProbe.normalize(" http://w:8081/ ")).isEqualTo("http://w:8081/health/ready");
        assertThat(HttpWorkerHealthProbe.normalize("http://w:8081/health/live")).isEqualTo("http://w:8081/health/live");
    }
}
