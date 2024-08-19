package dev.gruff.hardstop.resolver;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.*;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class Resolver {


    private static final Logger log = LoggerFactory.getLogger(Resolver.class);

    private final RepositorySystem system = newRepositorySystem();
    private final  DefaultRepositorySystemSession session;
    private LocalRepository localRepo;

    public Resolver() {
        this(new LocalRepository(localRepo()));
    }

    private static File localRepo() {
        File root=new File(System.getProperty("user.home"));
        File m2=new File(root,".m2");
        File repo=new File(m2,"repository");
        repo.mkdirs();
        return repo;
    }

    public Resolver(LocalRepository localRepo) {
        this.localRepo=localRepo;
        session=MavenRepositorySystemUtils.newSession();
        session.setRepositoryListener(new RepositoryListener(this) {
        });

    }

    public List<ArtifactResult> resolve(String d) throws DependencyResolutionException {
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

    private List<ArtifactResult> resolveAllVersions(String group, String artifact) throws DependencyResolutionException {
        VersionResolver vr=new VersionResolver();
        Set<String> versions=vr.resolve(group,artifact);
        List<ArtifactResult> results=new LinkedList<>();
        for(String v:versions) {
                List<ArtifactResult> ar=resolve0(group+":"+artifact+":"+v);
                results.addAll(ar);
        }
        return results;
    }

    private List<ArtifactResult> resolve0(String d)  {

        log.info("resolving {}",d);


        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));


        Dependency dependency = new Dependency(new DefaultArtifact(d), JavaScopes.COMPILE);

        RemoteRepository rr=new RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build();
        List<RemoteRepository> rrlist=new LinkedList<>();
        rrlist.add(rr);

        CollectRequest cr=new CollectRequest(dependency,rrlist);
        //CollectRequest collectRequest = new CollectRequest();
        //collectRequest.setRoot(dependency);

       DependencyFilter classpathFilter = null ; //DependencyFilterUtils
         //       .classpathFilter(JavaScopes.COMPILE, JavaScopes.RUNTIME);

        DependencyRequest dependencyRequest = new DependencyRequest(cr, classpathFilter);

        List<ArtifactResult> results= new LinkedList<>();
        try {
            DependencyResult dr = system.resolveDependencies(session, dependencyRequest);
            results=dr.getArtifactResults();
        } catch (DependencyResolutionException e) {
            log.info(e.getMessage());
        }
        log.info("resolved {} dependencies",results.size());
        return results;
    }

    public static RepositorySystem newRepositorySystem() {
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
}
