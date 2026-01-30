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

    public void resolvePOM(String d) {
        log.info("resolving POM for {}", d);
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));

        Artifact artifact = new DefaultArtifact(d + ":pom");
        ArtifactRequest artifactRequest = new ArtifactRequest();
        artifactRequest.setArtifact(artifact);
        artifactRequest.setRepositories(rrlist);

        try {
            ArtifactResult artifactResult = system.resolveArtifact(session, artifactRequest);
            log.info("resolved POM for {}", artifactResult.getArtifact());
        } catch (ArtifactResolutionException e) {
            log.info(e.getMessage());
        }
    }

    private  List<DependencyNode> resolve0(String d)  {

        log.info("resolving {}",d);


        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));


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


    public DependencySet resolveGraph(String gid, String aid, String first) {
        List<DependencyNode> roots=resolve0(gid+":"+aid+":"+first);
        return DependencyBuilder
                .newInstance()
                .addDependencies(roots)
                .build();
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
