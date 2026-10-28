package dev.by1337.bpatch.maven;

import dev.by1337.bpatch.Archives;
import dev.by1337.bpatch.PatchWriter;
import dev.by1337.bpatch.PatchSource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Resolves and embeds configured JARs and BPatch archives as project resources. */
@Mojo(name = "pack", defaultPhase = LifecyclePhase.PROCESS_RESOURCES, threadSafe = true)
public final class PackMojo extends AbstractMojo {
    @Component
    private RepositorySystem repositorySystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repositorySession;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Directory whose contents are included by the normal JAR packaging step. */
    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    private File outputDirectory;

    /** Individual dependencies to embed as repackaged JARs. */
    @Parameter
    private List<JarDependency> jars = new ArrayList<>();

    /** Ordered groups of dependency versions to encode as BPatch ZIPs. */
    @Parameter
    private List<PatchSet> patchSets = new ArrayList<>();

    /** Skip dependency resolution and packing. */
    @Parameter(property = "bpatch.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("BPatch packing skipped");
            return;
        }
        try {
            Set<Path> outputs = new HashSet<>();
            for (JarDependency jar : jars) {
                reserve(outputs, output(jar.output));
                requireArtifact(jar.artifact);
                jar.filter();
            }
            for (PatchSet set : patchSets) {
                reserve(outputs, output(set.output));
                if (set.versions.isEmpty()) {
                    throw new IllegalArgumentException("No versions configured for " + set.output);
                }
                Set<String> ids = new HashSet<>();
                for (PatchVersion version : set.versions) {
                    requireArtifact(version.artifact);
                    new PatchSource(version.id, Path.of("unused"));
                    if (!ids.add(version.id)) {
                        throw new IllegalArgumentException("Duplicate version ID: " + version.id);
                    }
                }
                set.filter();
            }
            for (JarDependency jar : jars) {
                Path source = resolve(jar.artifact);
                Path target = output(jar.output);
                Archives.repack(source, target, jar.compression, jar.filter());
                getLog().info("Embedded " + jar.artifact + " -> " + jar.output
                        + " (" + Files.size(target) + " bytes, " + jar.compression + ")");
            }
            for (PatchSet set : patchSets) {
                List<PatchSource> sources = new ArrayList<>();
                for (PatchVersion version : set.versions) {
                    sources.add(new PatchSource(version.id, resolve(version.artifact)));
                }
                Path target = output(set.output);
                PatchWriter.create(target, sources, set.mode, set.compression, set.filter());
                getLog().info("Packed " + sources.size() + " versions -> " + set.output
                        + " (" + Files.size(target) + " bytes, " + set.mode + ", " + set.compression + ")");
            }
        } catch (Exception e) {
            throw new MojoExecutionException("BPatch packing failed: " + e.getMessage(), e);
        }
    }

    private Path resolve(String coordinates) throws Exception {
        var artifact = new DefaultArtifact(coordinates);
        if (!"jar".equals(artifact.getExtension())) {
            throw new IllegalArgumentException("Expected a JAR dependency: " + coordinates);
        }
        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(artifact);
        request.setRepositories(project.getRemoteProjectRepositories());
        request.setRequestContext("bpatch");
        return repositorySystem.resolveArtifact(repositorySession, request).getArtifact().getFile().toPath();
    }

    private static void requireArtifact(String coordinates) {
        if (coordinates == null || coordinates.isBlank()) {
            throw new IllegalArgumentException("Missing artifact coordinates");
        }
        var artifact = new DefaultArtifact(coordinates);
        if (!"jar".equals(artifact.getExtension())) {
            throw new IllegalArgumentException("Expected a JAR dependency: " + coordinates);
        }
    }

    private Path output(String name) throws IOException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Missing output path");
        }
        Path relative = Path.of(name);
        Path root = outputDirectory.toPath().toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("Output must be a file below outputDirectory: " + name);
        }
        for (Path current = target; current != null && current.startsWith(root); current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Output path contains a symbolic link: " + current);
            }
        }
        return target;
    }

    private static void reserve(Set<Path> outputs, Path target) {
        for (Path previous : outputs) {
            if (previous.startsWith(target) || target.startsWith(previous)) {
                throw new IllegalArgumentException("Conflicting output paths: " + previous + " and " + target);
            }
        }
        outputs.add(target);
    }
}
