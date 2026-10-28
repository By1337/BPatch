package dev.by1337.bpatch;

import java.nio.file.Path;
import java.util.Objects;

public record PatchSource(String id, Path jar) {
    public PatchSource {
        if (id == null || !id.matches("[A-Za-z0-9_.+\\-]+")) {
            throw new IllegalArgumentException("Invalid version ID: " + id);
        }
        Objects.requireNonNull(jar, "jar");
    }
}
