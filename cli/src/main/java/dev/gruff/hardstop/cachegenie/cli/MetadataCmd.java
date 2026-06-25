package dev.gruff.hardstop.cachegenie.cli;

import dev.gruff.hardstop.cachegenie.actions.MetadataAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

/**
 * Synthesise {@code maven-metadata.xml} files into the local Maven repository from
 * the discovery catalogue (no network), so an offline resolve can handle version
 * ranges and {@code LATEST}/{@code RELEASE}. See {@link MetadataAction}.
 */
@CommandLine.Command(name = "metadata", aliases = {"gen-metadata"},
        description = "Write maven-metadata.xml files into the local repo from the catalogue (no network), "
                + "so offline resolution can handle version ranges / LATEST / RELEASE.")
public class MetadataCmd implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(MetadataCmd.class);

    @CommandLine.ParentCommand
    RootCmd parent;

    @CommandLine.Option(names = {"-gav", "--gav"}, paramLabel = "<selector>",
            description = "group or group:artifact to scope to. Omit to generate for the whole catalogue.")
    String gav;

    @CommandLine.Option(names = {"--repo-id"}, paramLabel = "<id>", defaultValue = "central",
            description = "Repository id used in the filename: maven-metadata-<id>.xml (default ${DEFAULT-VALUE}). "
                    + "Must match the remote repository's id (the offline resolver looks the file up by it).")
    String repoId;

    @CommandLine.Option(names = {"--also-plain"},
            description = "Also write plain maven-metadata.xml alongside the repo-id-suffixed file.")
    boolean alsoPlain;

    @Override
    public void run() {
        String gid = null, aid = null;
        if (gav != null && !gav.isBlank()) {
            String[] p = gav.trim().split(":");
            gid = p[0];
            if (p.length > 1 && !p[1].equals("*")) aid = p[1];
        }
        log.info("Generating maven-metadata files (repoId={}, gav={})", repoId, gav);
        new MetadataAction(parent.genie()).generate(gid, aid, repoId, alsoPlain);
        System.exit(0);
    }
}
