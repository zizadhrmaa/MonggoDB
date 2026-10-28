package monggodb.sm;

import java.util.Objects;

public record BlockId(String fileName, int blockId) {
    public BlockId {
        Objects.requireNonNull(fileName, "fileName must not be null");
        String trimmed = fileName.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("fileName must not be empty");
        }
        if (blockId < 0) {
            throw new IllegalArgumentException("blockId must be non-negative: " + blockId);
        }
        fileName = trimmed;
    }
}
