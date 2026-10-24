package com.blissfuljuan.dieapi.ops.service.port;

import com.blissfuljuan.dieapi.ops.model.WorkerHealth;

import java.util.List;

/** Port: asks each configured worker whether it is ready and how recent its heartbeat is. Never throws. */
public interface WorkerHealthProbe {

    List<WorkerHealth> probeAll();
}
