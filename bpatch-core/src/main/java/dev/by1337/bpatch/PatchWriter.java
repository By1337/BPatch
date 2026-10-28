package dev.by1337.bpatch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;

/** Build-time creation of BPatch archives; not needed to unpack them. */
public final class PatchWriter {
    private static final String INDEX = "index.properties";
    private static final String REMOVED = ".removed";

    private PatchWriter() {
    }

    public static void create(Path target, List<PatchSource> sources, PatchMode mode,
        Compression compression, Predicate<String> filter) throws IOException {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(compression, "compression");
        Objects.requireNonNull(filter, "filter");
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("At least one version is required");
        }
        var ids = new HashSet<String>();
        Path destination = target.toAbsolutePath().normalize();
        for (PatchSource source : sources) {
            if (!ids.add(source.id())) {
                throw new IllegalArgumentException("Duplicate version ID: " + source.id());
            }
            if (source.jar().toRealPath().equals(destination)
                    || (Files.exists(target) && Files.isSameFile(source.jar(), target))) {
                throw new IOException("Archive must not overwrite an input JAR: " + source.jar());
            }
        }
        Map<String, byte[]> archive = new TreeMap<>();
        Map<String, byte[]> base = null;
        Map<String, byte[]> previous = null;
        StringBuilder index = new StringBuilder("format=1\nversion.count=").append(sources.size()).append('\n');
        for (int i = 0; i < sources.size(); i++) {
            PatchSource source = sources.get(i);
            Map<String, byte[]> files = Archives.read(source.jar(), filter);
            for (String name : files.keySet()) {
                if (name.endsWith(REMOVED)) {
                    throw new IOException("Reserved removal suffix in source JAR: " + name);
                }
            }
            int parent = i == 0 ? -1 : mode == PatchMode.CHAIN ? i - 1 : 0;
            String key = "version." + i + ".";
            index.append(key).append("id=").append(source.id()).append('\n');
            index.append(key).append("parent=").append(parent).append('\n');
            index.append(key).append("sha256=").append(Archives.contentHash(files)).append('\n');
            String prefix = prefix(i);
            if (i == 0) {
                base = files;
                files.forEach((name, data) -> archive.put(prefix + name, data));
            } else {
                Map<String, byte[]> reference = mode == PatchMode.CHAIN ? previous : base;
                for (var item : files.entrySet()) {
                    if (!Arrays.equals(item.getValue(), reference.get(item.getKey()))) {
                        archive.put(prefix + item.getKey(), item.getValue());
                    }
                }
                for (String name : reference.keySet()) {
                    if (!files.containsKey(name)) {
                        archive.put(prefix + name + REMOVED, new byte[0]);
                    }
                }
            }
            previous = files;
        }
        archive.put(INDEX, index.toString().getBytes(StandardCharsets.ISO_8859_1));
        Archives.write(target, archive, compression);
    }

    private static String prefix(int ordinal) {
        return "versions/" + ordinal + "/";
    }
}
