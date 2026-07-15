package com.dupi.rag.dto.recovery;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateRestoreRequest(@NotNull UUID archiveId) {
}
