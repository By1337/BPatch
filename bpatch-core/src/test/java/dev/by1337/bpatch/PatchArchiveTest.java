package dev.by1337.bpatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PatchArchiveTest {
    @TempDir
    Path directory;

    static Stream<Arguments> modes() {
        return Stream.of(PatchMode.values()).flatMap(mode -> Stream.of(Compression.values())
                .map(compression -> Arguments.of(mode, compression)));
    }

    @ParameterizedTest
    @MethodSource("modes")
    void roundTripChangesRemovalsReintroductionsAndResources(PatchMode mode, Compression compression) throws Exception {
        Map<String, byte[]> first = files("A.class", "first", "Gone.class", "gone",
                "same.class", "same", "config.yml", "initial", "META-INF/MANIFEST.MF", "manifest");
        Map<String, byte[]> second = files("A.class", "second", "Added.class", "added",
                "same.class", "same", "config.yml", "updated", "empty", "");
        Map<String, byte[]> third = files("A.class", "third", "Gone.class", "returned",
                "same.class", "same", "config.yml", "third");
        List<Map<String, byte[]>> targets = List.of(first, second, third);
        List<PatchSource> sources = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            Path source = directory.resolve(i + ".jar");
            Archives.write(source, targets.get(i), Compression.DEFLATED);
            sources.add(new PatchSource("v" + i, source));
        }
        Path packed = directory.resolve("patch.zip");
        PatchWriter.create(packed, sources, mode, compression, ResourceFilter.all());
        Path repeated = directory.resolve("repeated.zip");
        PatchWriter.create(repeated, sources, mode, compression, ResourceFilter.all());
        assertArrayEquals(Files.readAllBytes(packed), Files.readAllBytes(repeated));
        try (var zip = new java.util.zip.ZipFile(packed.toFile())) {
            assertNotNull(zip.getEntry("versions/1/Gone.class.removed"));
            assertNull(zip.getEntry("versions/1/same.class"));
            assertTrue(zip.stream().allMatch(entry -> entry.getMethod() == compression.method()));
        }
        try (PatchArchive archive = PatchArchive.open(packed)) {
            assertEquals(List.of("v0", "v1", "v2"), archive.versions().stream().map(PatchArchive.Version::id).toList());
            assertEquals(mode == PatchMode.CHAIN ? "v1" : "v0", archive.versions().get(2).parent());
            // Read out of order: no prior call or mutable decoder state is required.
            for (int i : new int[]{ 2, 0, 1 }) {
                Path restored = directory.resolve("restored-" + i + ".jar");
                archive.unpack("v" + i, restored);
                assertContents(targets.get(i), Archives.read(restored, ResourceFilter.all()));
            }
            assertThrows(IOException.class, () -> archive.readVersion("missing"));
            assertThrows(IOException.class, () -> archive.unpack("v0", packed));
        }
        try (PatchArchive archive = PatchArchive.open(Files.newInputStream(packed))) {
            assertContents(third, archive.readVersion("v2"));
        }
    }

    @Test
    void appliesSameFilterBeforeComputingPatches() throws Exception {
        Path a = jar("a.jar", files("A.class", "a", "META-INF/services/api", "provider a", "res.yml", "keep"));
        Path b = jar("b.jar", files("A.class", "b", "META-INF/services/api", "provider b", "res.yml", "keep"));
        ResourceFilter filter = new ResourceFilter(List.of("**/*.class", "**/*.yml"), List.of("META-INF/**"));
        assertTrue(filter.test("A.class"));
        assertTrue(filter.test("nested/A.class"));
        assertFalse(filter.test("META-INF/X.class"));
        assertFalse(filter.test("readme.txt"));
        Path packed = directory.resolve("filtered.zip");
        PatchWriter.create(packed, List.of(new PatchSource("a", a), new PatchSource("b", b)),
                PatchMode.CHAIN, Compression.STORED, filter);
        try (PatchArchive archive = PatchArchive.open(packed)) {
            assertContents(files("A.class", "b", "res.yml", "keep"), archive.readVersion("b"));
        }
    }

    @Test
    void handlesIdenticalVersionsAndEmptyFilteredArchives() throws Exception {
        Path source = jar("source.jar", files("A.class", "a"));
        List<PatchSource> sources = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            sources.add(new PatchSource("v" + i, source));
        }
        for (boolean empty : List.of(false, true)) {
            Path packed = directory.resolve("identical-" + empty + ".zip");
            PatchWriter.create(packed, sources, PatchMode.CHAIN, Compression.STORED, n -> !empty);
            try (PatchArchive archive = PatchArchive.open(packed)) {
                assertEquals(20, archive.versions().size());
                assertContents(empty ? Map.of() : files("A.class", "a"), archive.readVersion("v19"));
            }
        }
    }

    @Test
    void detectsCorruptionBeforeReplacingOutput() throws Exception {
        Path source = jar("a.jar", files("A.class", "a"));
        Path packed = directory.resolve("patch.zip");
        PatchWriter.create(packed, List.of(new PatchSource("a", source)), PatchMode.CHAIN,
                Compression.STORED, ResourceFilter.all());
        Map<String, byte[]> corrupt = Archives.read(packed, ResourceFilter.all());
        corrupt.put("versions/0/A.class", "corrupt".getBytes(StandardCharsets.UTF_8));
        Archives.write(packed, corrupt, Compression.STORED);
        Path target = directory.resolve("keep.jar");
        Files.writeString(target, "keep");
        try (PatchArchive archive = PatchArchive.open(packed)) {
            assertThrows(IOException.class, () -> archive.unpack("a", target));
        }
        assertEquals("keep", Files.readString(target));
    }

    @Test
    void rejectsInvalidInputsAndIndexes() throws Exception {
        Path source = jar("source.jar", files("A.class", "a"));
        Path packed = directory.resolve("patch.zip");
        assertThrows(IllegalArgumentException.class, () -> PatchWriter.create(packed, List.of(),
                PatchMode.CHAIN, Compression.STORED, ResourceFilter.all()));
        var same = new PatchSource("same", source);
        assertThrows(IllegalArgumentException.class, () -> PatchWriter.create(packed, List.of(same, same),
                PatchMode.CHAIN, Compression.STORED, ResourceFilter.all()));
        Path reserved = jar("reserved.jar", files("A.class.removed", ""));
        assertThrows(IOException.class, () -> PatchWriter.create(packed, List.of(new PatchSource("a", reserved)),
                PatchMode.CHAIN, Compression.STORED, ResourceFilter.all()));
        assertThrows(IOException.class, () -> PatchWriter.create(source, List.of(same),
                PatchMode.CHAIN, Compression.STORED, ResourceFilter.all()));
        assertThrows(IOException.class, () -> PatchArchive.open(source));
        PatchWriter.create(packed, List.of(same), PatchMode.CHAIN, Compression.STORED, ResourceFilter.all());
        Map<String, byte[]> broken = Archives.read(packed, ResourceFilter.all());
        broken.put("index.properties", "format=1\nversion.count=1\nversion.0.id=a\nversion.0.parent=0\nversion.0.sha256=bad\n"
                .getBytes(StandardCharsets.UTF_8));
        Archives.write(packed, broken, Compression.STORED);
        assertThrows(IOException.class, () -> PatchArchive.open(packed));
    }

    @Test
    void repacksJarsAndRejectsUnsafeOrInvalidZipInputs() throws Exception {
        Path source = jar("source.jar", files("A.class", "a", "META-INF/test", "metadata"));
        Path target = directory.resolve("stored.jar");
        Archives.repack(source, target, Compression.STORED,
                new ResourceFilter(List.of(), List.of("META-INF/")));
        assertContents(files("A.class", "a"), Archives.read(target, ResourceFilter.all()));
        assertThrows(IOException.class, () -> Archives.repack(source, source, Compression.STORED, ResourceFilter.all()));
        Path unsafe = directory.resolve("unsafe.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(unsafe))) {
            zip.putNextEntry(new ZipEntry("../outside"));
            zip.write(1);
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> Archives.read(unsafe, ResourceFilter.all()));
        Path invalid = directory.resolve("invalid.jar");
        Files.writeString(invalid, "not a ZIP");
        assertThrows(IOException.class, () -> Archives.read(invalid, ResourceFilter.all()));
    }

    private Path jar(String name, Map<String, byte[]> entries) throws IOException {
        Path target = directory.resolve(name);
        Archives.write(target, entries, Compression.DEFLATED);
        return target;
    }

    private static Map<String, byte[]> files(String... pairs) {
        Map<String, byte[]> entries = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            entries.put(pairs[i], pairs[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return entries;
    }

    private static void assertContents(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((name, data) -> assertArrayEquals(data, actual.get(name), name));
    }
}
