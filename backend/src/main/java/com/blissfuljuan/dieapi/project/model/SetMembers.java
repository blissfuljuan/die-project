package com.blissfuljuan.dieapi.project.model;

import java.util.List;
import java.util.UUID;

/** Staff command: replace the member set (and optionally the representative) of a project. */
public record SetMembers(Long version, List<UUID> memberIds, UUID representativeId, Boolean allowSizeException) {}
