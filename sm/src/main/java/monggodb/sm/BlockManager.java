package monggodb.sm;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public final class BlockManager {
    public static final int DEFAULT_BLOCK_SIZE = 4096;

    private final Path dataDirectory;
    private final int blockSize;

    public BlockManager(Path dataDirectory) {
        this(dataDirectory, DEFAULT_BLOCK_SIZE);
    }

    public BlockManager(Path dataDirectory, int blockSize) {
        Objects.requireNonNull(dataDirectory, "dataDirectory must not be null");
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive: " + blockSize);
        }
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.blockSize = blockSize;
        try {
            Files.createDirectories(this.dataDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create data directory: " + this.dataDirectory, e);
        }
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public int getBlockSize() {
        return blockSize;
    }

    public Path resolveFile(String fileName) {
        Objects.requireNonNull(fileName, "fileName must not be null");
        String trimmed = fileName.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("fileName must not be empty");
        }
        Path resolved = dataDirectory.resolve(trimmed).normalize();
        if (!resolved.startsWith(dataDirectory)) {
            throw new IllegalArgumentException("Invalid file path outside data directory: " + fileName);
        }
        return resolved;
    }

    public synchronized boolean fileExists(String fileName) {
        return Files.exists(resolveFile(fileName));
    }

    public synchronized void createFileIfNotExists(String fileName) {
        Path file = resolveFile(fileName);
        if (Files.exists(file)) {
            return;
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.createFile(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create file: " + fileName, e);
        }
    }

    public synchronized void deleteFile(String fileName) {
        Path file = resolveFile(fileName);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete file: " + fileName, e);
        }
    }

    public synchronized void truncateFile(String fileName) {
        Path file = resolveFile(fileName);
        createFileIfNotExists(fileName);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(0);
            raf.getChannel().force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to truncate file: " + fileName, e);
        }
    }

    public synchronized int getBlockCount(String fileName) {
        Path file = resolveFile(fileName);
        if (!Files.exists(file)) {
            return 0;
        }
        try {
            long size = Files.size(file);
            return Math.toIntExact((size + blockSize - 1) / blockSize);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read file size: " + fileName, e);
        }
    }

    public synchronized byte[] readBlock(String fileName, int blockId) {
        byte[] buffer = new byte[blockSize];
        readBlock(fileName, blockId, buffer);
        return buffer;
    }

    public synchronized void readBlock(String fileName, int blockId, byte[] destination) {
        Objects.requireNonNull(destination, "destination must not be null");
        if (destination.length != blockSize) {
            throw new IllegalArgumentException("Destination length must match blockSize: " + blockSize);
        }
        if (blockId < 0) {
            throw new IllegalArgumentException("blockId must be non-negative: " + blockId);
        }
        Path file = resolveFile(fileName);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("File does not exist: " + fileName);
        }
        long offset = (long) blockId * blockSize;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            if (offset >= raf.length()) {
                throw new IndexOutOfBoundsException("Block " + blockId + " out of range for file " + fileName);
            }
            raf.seek(offset);
            int totalRead = 0;
            while (totalRead < blockSize) {
                int bytesRead = raf.read(destination, totalRead, blockSize - totalRead);
                if (bytesRead < 0) {
                    break;
                }
                totalRead += bytesRead;
            }
            if (totalRead < blockSize) {
                for (int i = totalRead; i < blockSize; i++) {
                    destination[i] = 0;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read block " + blockId + " from " + fileName, e);
        }
    }

    public synchronized void writeBlock(String fileName, int blockId, byte[] data) {
        Objects.requireNonNull(data, "data must not be null");
        if (data.length != blockSize) {
            throw new IllegalArgumentException("Data length must match blockSize: " + blockSize);
        }
        if (blockId < 0) {
            throw new IllegalArgumentException("blockId must be non-negative: " + blockId);
        }
        createFileIfNotExists(fileName);
        Path file = resolveFile(fileName);
        long offset = (long) blockId * blockSize;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            if (offset > raf.length()) {
                throw new IndexOutOfBoundsException("Cannot write past EOF at block " + blockId + " in " + fileName);
            }
            raf.seek(offset);
            raf.write(data, 0, blockSize);
            raf.getChannel().force(false);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write block " + blockId + " to " + fileName, e);
        }
    }

    public synchronized int appendBlock(String fileName) {
        return appendBlock(fileName, new byte[blockSize]);
    }

    public synchronized int appendBlock(String fileName, byte[] data) {
        Objects.requireNonNull(data, "data must not be null");
        if (data.length != blockSize) {
            throw new IllegalArgumentException("Data length must match blockSize: " + blockSize);
        }
        createFileIfNotExists(fileName);
        Path file = resolveFile(fileName);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            int newBlockId = Math.toIntExact((raf.length() + blockSize - 1) / blockSize);
            long offset = (long) newBlockId * blockSize;
            raf.seek(offset);
            raf.write(data, 0, blockSize);
            raf.getChannel().force(false);
            return newBlockId;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append block to " + fileName, e);
        }
    }

    public synchronized List<String> listFiles(String suffix) {
        try (Stream<Path> stream = Files.list(dataDirectory)) {
            return stream
                .filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .filter(name -> suffix == null || suffix.isEmpty() || name.endsWith(suffix))
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list files in " + dataDirectory, e);
        }
    }

    public synchronized void copyAllFilesTo(Path targetDirectory) {
        Objects.requireNonNull(targetDirectory, "targetDirectory must not be null");
        copyDirectoryContents(this.dataDirectory, targetDirectory.toAbsolutePath().normalize());
    }

    public synchronized void restoreAllFilesFrom(Path sourceDirectory) {
        Objects.requireNonNull(sourceDirectory, "sourceDirectory must not be null");
        Path normalizedSource = sourceDirectory.toAbsolutePath().normalize();
        if (!Files.exists(normalizedSource)) {
            throw new IllegalArgumentException("Snapshot directory does not exist: " + sourceDirectory);
        }
        copyDirectoryContents(normalizedSource, this.dataDirectory);
    }

    private void copyDirectoryContents(Path sourceDir, Path targetDir) {
        try {
            Files.createDirectories(targetDir);
            try (Stream<Path> targetFiles = Files.list(targetDir)) {
                for (Path existing : targetFiles.filter(Files::isRegularFile).toList()) {
                    String name = existing.getFileName().toString();
                    if (name.endsWith(".tbl") || name.endsWith(".idx") || name.equals("catalog.json")) {
                        Files.deleteIfExists(existing);
                    }
                }
            }
            try (Stream<Path> sourceFiles = Files.list(sourceDir)) {
                for (Path file : sourceFiles.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".tbl") || name.endsWith(".idx") || name.equals("catalog.json")) {
                        Files.copy(file, targetDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to copy data files between " + sourceDir + " and " + targetDir, e);
        }
    }
}
