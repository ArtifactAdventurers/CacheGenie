package dev.gruff.hardstop.resolver;

import dev.gruff.hardstop.cachegenie.CacheGenie;

import dev.gruff.hardstop.cachegenie.entities.ArtifactRef;
import dev.gruff.hardstop.cachegenie.utils.ObjectChecks;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.*;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class Resolver {

    private static final Logger log = LoggerFactory.getLogger(Resolver.class);

    private final RepositorySystem system;
    private final  DefaultRepositorySystemSession session;
    private final LocalRepository localRepo;
    private final List<RemoteRepository> rrlist;

    public ArtifactRef resolveArtifact(String s) {
        try {
            List<DependencyNode> results= resolve(s);
            if(results==null || results.isEmpty()) return null;
            DependencyNode dn=results.getFirst();
            Artifact a=dn.getArtifact();
            File code=a.getFile();
            if(code!=null) {
                return ArtifactRef.create(a.getGroupId(),a.getArtifactId(),a.getVersion(),code);
            }

            return ArtifactRef.create(a.getGroupId(),a.getArtifactId(),a.getVersion());

        } catch (DependencyResolutionException e) {
         e.printStackTrace();
         return null;
        }
    }

    private static class RepoConfig {
        private String name;
        private String type;
        private String uri;
    }
    private Resolver(CacheGenie cg, Set<RepoConfig> repos, boolean localOnly) {

        this.localRepo=new LocalRepository(cg.repoRoot());
        this.system= newRepositorySystem();

        session=MavenRepositorySystemUtils.newSession();
        session.setRepositoryListener(new RepositoryListener(this) {
        });

        // Use the SIMPLE local-repository manager: unlike the default "enhanced"
        // one it does not track per-artifact remote origin (_remote.repositories),
        // so cached artifacts are trusted as-is with no network re-verification.
        // Faster for read/resolve-heavy work like 'graph deps'. Set once here.
        try {
            session.setLocalRepositoryManager(
                    new org.eclipse.aether.internal.impl.SimpleLocalRepositoryManagerFactory()
                            .newInstance(session, localRepo));
        } catch (org.eclipse.aether.repository.NoLocalRepositoryManagerException e) {
            session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
        }

        rrlist = new LinkedList<>();

        if(!localOnly) {


            if (repos != null && !repos.isEmpty()) {
                for (RepoConfig c : repos) {
                    RemoteRepository rr = new RemoteRepository.Builder(c.name, c.type, c.uri).build();
                    rrlist.add(rr);
                }
            } else {
                RemoteRepository rr = new RemoteRepository.Builder("central", "default", cg.base().toASCIIString()).build();
                rrlist.add(rr);
            }
        }





    }


    public List<DependencyNode> resolve(String d) throws DependencyResolutionException {
            if(d==null) return List.of();
            d=d.trim();
            if(d.equals("")) return List.of();

            String[] parts=d.split(":");

            if(parts.length<2) return List.of();

            if(parts.length>3) return List.of();

            if(parts.length==2) {
                return resolveAllVersions(parts[0],parts[1]);
            }
            else {
                return resolve0(d);
            }
    }

    private List<DependencyNode> resolveAllVersions(String group, String artifact) throws DependencyResolutionException {
        VersionResolver vr=new VersionResolver();
        Set<String> versions=vr.resolve(group,artifact);
        List<DependencyNode> results=new LinkedList<>();
        for(String v:versions) {
            List<DependencyNode> dn=resolve0(group+":"+artifact+":"+v);
                results.addAll(dn);
        }
        return results;
    }

    public boolean resolvePOM(String d) {
        log.info("resolving POM for {}", d);

        String[] parts = d.split(":");
        if (parts.length < 3) {
            log.error("Invalid GAV for POM resolution: {}", d);
            return false;
        }

        Artifact artifact = new DefaultArtifact(parts[0], parts[1], "pom", parts[2]);
        ArtifactRequest artifactRequest = new ArtifactRequest();
        artifactRequest.setArtifact(artifact);
        artifactRequest.setRepositories(rrlist);

        try {
            ArtifactResult artifactResult = system.resolveArtifact(session, artifactRequest);
            log.info("resolved POM for {}", artifactResult.getArtifact());
            return true;
        } catch (ArtifactResolutionException e) {
            log.info(e.getMessage());
            return false;
        }
    }

    /** A single direct dependency edge with its effective version and scope. */
    public record DirectDep(String gid, String aid, String version, String scope) {}

    /**
     * Why a descriptor read ended. {@code OK}: deps available. {@code NOT_FOUND}:
     * the POM (or a required parent/BOM) genuinely isn't there — safe to mark
     * missing. {@code RATE_LIMITED}: the remote returned 429 — we're overloading
     * it, callers should stop. {@code TRANSIENT}: a temporary error (5xx, timeout,
     * connection) — don't mark missing, retry later.
     */
    public enum ResolveOutcome { OK, NOT_FOUND, RATE_LIMITED, TRANSIENT }

    /** Outcome of {@link #directDependencies}; {@code deps} non-null only when {@code outcome == OK}. */
    public record DirectDepsResult(ResolveOutcome outcome, List<DirectDep> deps) {}

    /**
     * Outcome of a raw {@code .pom} fetch (see {@link PomFetcher}); {@code file} is
     * non-null only when {@code outcome == OK}. Lives here so it can share
     * {@link ResolveOutcome} with the descriptor-read path.
     */
    public record PomFetch(ResolveOutcome outcome, File file) {}

    /**
     * Read an artifact's <em>direct</em> dependencies from its effective POM
     * (parent inheritance, imported BOMs and properties applied, managed versions
     * resolved) WITHOUT collecting the transitive tree. This is the cheap building
     * block for an ecosystem-wide graph: one descriptor read per artifact, with the
     * transitive closure computed later in SQL (recursive CTE).
     *
     * @return an outcome the caller can act on (see {@link ResolveOutcome}).
     */
    public DirectDepsResult directDependencies(String gav) {
        try {
            Artifact artifact = new DefaultArtifact(gav);
            ArtifactDescriptorRequest request = new ArtifactDescriptorRequest(artifact, rrlist, null);
            ArtifactDescriptorResult result = system.readArtifactDescriptor(session, request);
            List<DirectDep> out = new LinkedList<>();
            for (Dependency d : result.getDependencies()) {
                Artifact da = d.getArtifact();
                if (da == null) continue;
                out.add(new DirectDep(da.getGroupId(), da.getArtifactId(), da.getVersion(), d.getScope()));
            }
            return new DirectDepsResult(ResolveOutcome.OK, out);
        } catch (ArtifactDescriptorException e) {
            ResolveOutcome outcome = classify(e);
            log.debug("Descriptor read for {} -> {}: {}", gav, outcome, e.getMessage());
            return new DirectDepsResult(outcome, null);
        }
    }

    /**
     * Classify a descriptor-read failure by walking the Aether exception chain.
     * 429 wins (we're overloading the remote); then genuine not-found; otherwise
     * treat as transient (do not mark missing). Heuristic — message/type based —
     * but the 429 detection is what guards against hammering the remote.
     */
    private static ResolveOutcome classify(Throwable e) {
        boolean transientErr = false;
        boolean notFound = false;
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null) {
                String ml = m.toLowerCase();
                // 429 wins outright — we're overloading the remote.
                if (ml.contains("429") || ml.contains("too many requests")) {
                    return ResolveOutcome.RATE_LIMITED;
                }
                // Temporary: server errors, timeouts, connection issues — retry later.
                if (ml.contains("status code: 50") || ml.contains("503") || ml.contains("502")
                        || ml.contains("timed out") || ml.contains("timeout")
                        || ml.contains("connection reset") || ml.contains("connection refused")
                        || ml.contains("could not transfer")) {
                    transientErr = true;
                }
                // Definitive not-found phrasings (incl. unresolvable parent POMs).
                if (ml.contains("could not find artifact") || ml.contains("failure to find")
                        || ml.contains("non-resolvable parent")) {
                    notFound = true;
                }
            }
            if (t instanceof org.eclipse.aether.transfer.ArtifactNotFoundException) {
                notFound = true;
            }
        }
        // Prefer not marking missing when in doubt: transient beats not-found.
        if (transientErr) return ResolveOutcome.TRANSIENT;
        if (notFound) return ResolveOutcome.NOT_FOUND;
        return ResolveOutcome.TRANSIENT;
    }

    private  List<DependencyNode> resolve0(String d)  {

        log.info("resolving {}",d);




        Dependency dependency = new Dependency(new DefaultArtifact(d), JavaScopes.COMPILE);

       // RemoteRepository rr=new RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build();
       // List<RemoteRepository> rrlist=new LinkedList<>();
       // rrlist.add(rr);

        CollectRequest cr=new CollectRequest(dependency,rrlist);
        //CollectRequest collectRequest = new CollectRequest();
        //collectRequest.setRoot(dependency);

       DependencyFilter classpathFilter = null ; //DependencyFilterUtils
         //       .classpathFilter(JavaScopes.COMPILE, JavaScopes.RUNTIME);

        DependencyRequest dependencyRequest = new DependencyRequest(cr, classpathFilter);

        DependencyNode results=null;
        try {
            DependencyResult dr = system.resolveDependencies(session, dependencyRequest);
            results=dr.getRoot();
            log.info("resolved {} dependencies",dr.getArtifactResults().size());
            //results=dr.getArtifactResults();

        } catch (DependencyResolutionException e) {

            log.info(e.getMessage());
        }
        if(results==null) return List.of();
        else return List.of(results);
    }

    private static RepositorySystem newRepositorySystem() {
        /*
         * Aether's components implement org.eclipse.aether.spi.locator.Service to ease manual wiring and to provide
         * a simple plug-in mechanism. In the future, we might want to use a proper IoC container like Guice or
         * Spring.
         */
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);

        locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {
            @Override
            public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
                exception.printStackTrace();
            }
        });

        return locator.getService(RepositorySystem.class);
    }


    public static ResolverBuilder Builder(CacheGenie cg) {
        return new ResolverBuilder(cg);
    }
    public static class ResolverBuilder {
        private CacheGenie genie=null;
        private boolean localOnly=false;
        private Set<RepoConfig> repos=new HashSet<>();
        private ResolverBuilder(CacheGenie cg) {
            ObjectChecks.isPresent("cg",cg);
            this.genie=cg;
        }

        public ResolverBuilder localOnly() {
            this.localOnly=true;
            return this;
        }
        public Resolver build() {
            Resolver r=new Resolver(genie,repos,localOnly);
            return r;
        }
    }
}
