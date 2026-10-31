package monggodb.sm.buffer;

import java.util.Objects;

public record PageKey(String fileName, int pageId) {
    public PageKey {
        Objects.requireNonNull(fileName, "fileName must not be null");
        fileName = fileName.trim();
        if (fileName.isEmpty()) {
            throw new IllegalArgumentException("fileName must not be empty");
        }
        if (pageId < 0) {
            throw new IllegalArgumentException("pageId must be non-negative: " + pageId);
        }
    }

    public String getFileName() {
        return fileName;
    }

    public int getPageId() {
        return pageId;
    }
}
