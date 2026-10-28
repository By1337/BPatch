package dev.by1337.bpatch.maven;

import dev.by1337.bpatch.Compression;
import dev.by1337.bpatch.PatchMode;

import java.util.ArrayList;
import java.util.List;

public final class PatchSet extends ResourceOptions {
    public String output;
    public Compression compression = Compression.STORED;
    public PatchMode mode = PatchMode.CHAIN;
    public List<PatchVersion> versions = new ArrayList<>();
}
