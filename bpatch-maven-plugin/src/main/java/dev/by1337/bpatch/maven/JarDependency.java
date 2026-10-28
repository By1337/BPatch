package dev.by1337.bpatch.maven;

import dev.by1337.bpatch.Compression;

public final class JarDependency extends ResourceOptions {
    public String artifact;
    public String output;
    public Compression compression = Compression.STORED;
}
