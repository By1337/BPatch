package dev.by1337.bpatch;

import java.nio.file.Path;

/** Optional command-line interface; the same JAR is usable as a library. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("list")) {
            try (PatchArchive archive = PatchArchive.open(Path.of(args[1]))) {
                for (PatchArchive.Version version : archive.versions()) {
                    System.out.println(version.id() + (version.parent() == null ? " (base)" : " <- " + version.parent()));
                }
            }
        } else if (args.length == 4 && args[0].equals("unpack")) {
            try (PatchArchive archive = PatchArchive.open(Path.of(args[1]))) {
                archive.unpack(args[2], Path.of(args[3]));
            }
        } else {
            System.err.println("Usage: java -jar bpatch-runtime.jar list <archive.zip>");
            System.err.println("       java -jar bpatch-runtime.jar unpack <archive.zip> <version> <output.jar>");
            System.exit(2);
        }
    }
}
