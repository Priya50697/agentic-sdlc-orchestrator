package com.sdlcagent.orchestrator.agents.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/** Scratch copy of the workspace (without target/ and .git) so tests can be run before their files are accepted. */
final class WorkspaceCopy {
    private WorkspaceCopy() {}

    static void copy(Path from, Path to) {
        Path src = from.toAbsolutePath().normalize();
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path rel = src.relativize(p);
                String first = rel.getNameCount() == 0 ? "" : rel.getName(0).toString();
                if (first.equals("target") || first.equals(".git")) continue;
                Path dest = to.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void delete(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // best effort: scratch dirs live under the system temp directory
        }
    }
}
