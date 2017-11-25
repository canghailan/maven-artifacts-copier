package cc.whohow.maven;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.ArtifactType;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.artifact.DefaultArtifactType;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.deployment.DeployRequest;
import org.eclipse.aether.deployment.DeploymentException;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.*;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transfer.ArtifactNotFoundException;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.artifact.SubArtifact;
import org.eclipse.aether.version.Version;

import java.io.File;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MavenArtifactCopier {
    public static List<ArtifactType> defaultSubArtifactTypes() {
        List<ArtifactType> artifactTypes = new ArrayList<>();
        artifactTypes.add(new DefaultArtifactType("pom"));
        artifactTypes.add(new DefaultArtifactType("test-jar", "jar", "tests", "java"));
        artifactTypes.add(new DefaultArtifactType("javadoc", "jar", "javadoc", "java"));
        artifactTypes.add(new DefaultArtifactType("java-source", "jar", "sources", "java", false, false));
        return artifactTypes;
    }

    private static final Logger LOG = LogManager.getLogger();

    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final RemoteRepository source;
    private final RemoteRepository target;
    private List<ArtifactType> subArtifactTypes;
    private boolean verbose = true;

    public MavenArtifactCopier(RemoteRepository source, RemoteRepository target) {
        this(source, target, new LocalRepository("repository"));
    }

    public MavenArtifactCopier(RemoteRepository source, RemoteRepository target, LocalRepository local) {
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);

        RepositorySystem system = locator.getService(RepositorySystem.class);

        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, local));

        this.system = system;
        this.session = session;
        this.source = source;
        this.target = target;
        this.subArtifactTypes = defaultSubArtifactTypes();
    }

    public MavenArtifactCopier(RepositorySystem system, RepositorySystemSession session,
                               RemoteRepository source, RemoteRepository target) {
        this.system = system;
        this.session = session;
        this.source = source;
        this.target = target;
        this.subArtifactTypes = defaultSubArtifactTypes();
    }

    public void setSubArtifactTypes(List<ArtifactType> subArtifactTypes) {
        this.subArtifactTypes = subArtifactTypes;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public List<SubArtifact> getSubArtifact(Artifact artifact) {
        List<SubArtifact> subArtifactList = new ArrayList<>();
        for (ArtifactType artifactType : subArtifactTypes) {
            subArtifactList.add(new SubArtifact(artifact, artifactType.getClassifier(), artifactType.getExtension()));
        }
        return subArtifactList;
    }

    public void copy(String coords) {
        LOG.info("copy {}", coords);
        diffVersion(coords).forEach(this::copy);
    }

    public void copy(Artifact artifact) {
        LOG.info("copy {}", artifact);
        deploy(resolve(artifact));
    }

    public List<Artifact> diffVersion(String coords) {
        verbose("resolve\t %s\n", coords);
        Artifact artifact = new DefaultArtifact(coords);
        List<String> sourceVersions = resolveVersionRange(newVersionRangeRequest(artifact, source)).getVersions()
                .stream()
                .map(Version::toString)
                .collect(Collectors.toList());
        verbose("source\t %s\n", sourceVersions);
        List<String> targetVersions = resolveVersionRange(newVersionRangeRequest(artifact, target)).getVersions()
                .stream()
                .map(Version::toString)
                .collect(Collectors.toList());
        verbose("target\t %s\n", targetVersions);

        sourceVersions.removeAll(targetVersions);
        List<Artifact> artifactList = new ArrayList<>();
        for (String version : sourceVersions) {
            artifactList.add(new DefaultArtifact(
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    artifact.getClassifier(),
                    artifact.getExtension(),
                    version
            ));
        }
        return artifactList;
    }

    public List<Artifact> resolve(Artifact artifact) {
        verbose("resolve\t %s\n", artifact);
        return Stream.concat(Stream.of(artifact), getSubArtifact(artifact).stream())
                .map(this::newArtifactRequest)
                .map(this::resolve)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public void deploy(List<Artifact> artifacts) {
        verbose("deploy\t %s\n", artifacts);
        deploy(newDeployRequest(artifacts));
    }

    private VersionRangeRequest newVersionRangeRequest(Artifact artifact, RemoteRepository repository) {
        VersionRangeRequest versionRangeRequest = new VersionRangeRequest();
        versionRangeRequest.setArtifact(artifact);
        versionRangeRequest.setRepositories(Collections.singletonList(repository));
        return versionRangeRequest;
    }

    private ArtifactRequest newArtifactRequest(Artifact artifact) {
        ArtifactRequest artifactRequest = new ArtifactRequest();
        artifactRequest.setArtifact(artifact);
        artifactRequest.setRepositories(Collections.singletonList(source));
        return artifactRequest;
    }

    private DeployRequest newDeployRequest(List<Artifact> artifacts) {
        DeployRequest deployRequest = new DeployRequest();
        for (Artifact artifact : artifacts) {
            File file = new File(
                    session.getLocalRepository().getBasedir(),
                    session.getLocalRepositoryManager().getPathForLocalArtifact(artifact));
            LOG.debug("{} {}", artifact, file);
            deployRequest.addArtifact(artifact.setFile(file));
        }
        deployRequest.setRepository(target);
        return deployRequest;
    }

    private VersionRangeResult resolveVersionRange(VersionRangeRequest request) {
        try {
            return system.resolveVersionRange(session, request);
        } catch (VersionRangeResolutionException e) {
            throw new UndeclaredThrowableException(e);
        }
    }

    private Artifact resolve(ArtifactRequest request) {
        try {
            system.resolveArtifact(session, request);
            return request.getArtifact();
        } catch (ArtifactResolutionException e) {
            if (e.getCause() instanceof ArtifactNotFoundException) {
                LOG.warn(e);
                return null;
            } else {
                throw new UndeclaredThrowableException(e);
            }
        }
    }

    private void deploy(DeployRequest request) {
        try {
            system.deploy(session, request);
        } catch (DeploymentException e) {
            throw new UndeclaredThrowableException(e);
        }
    }

    private void verbose(String format, Object... args) {
        if (verbose) {
            System.out.printf(format, args);
        }
    }
}
