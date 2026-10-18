package com.blissfuljuan.dieapi.project.model;

import java.util.List;
import java.util.UUID;

/** Command to create a project; {@code kind} is the raw client value and is parsed by the service. */
public record CreateProject(String kind, String title, String description, String teamName, UUID participantId,
                            List<UUID> memberIds, UUID representativeId, Boolean allowSizeException) {}
