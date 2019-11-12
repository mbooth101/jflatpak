package uk.co.matbooth.jflatpak.maven;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.transfer.dependencies.DependableCoordinate;
import org.apache.maven.shared.transfer.dependencies.resolve.DependencyResolverException;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.ArtifactType;
import org.eclipse.aether.artifact.ArtifactTypeRegistry;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.artifact.DefaultArtifactType;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.internal.impl.DefaultArtifactResolver;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;

/**
 * A custom dependency and artifact resolver that returns artifacts complete
 * with information about from which repositories the artifacts were resolved.
 * The artifacts returned from the standard {@link DefaultArtifactResolver} have
 * the repository information omitted, so we re-implement it here such that the
 * results contain the information we need.
 */
@Component(role = CustomResolver.class)
class CustomResolver {

	@Requirement
	private RepositorySystem repositorySystem;

	@Requirement
	private ArtifactHandlerManager artifactHandlerManager;

	public Iterable<Artifact> resolveDependency(ProjectBuildingRequest buildingRequest, DependableCoordinate coordinate,
			boolean includePoms) throws DependencyResolverException, ArtifactResolverException {

		ArtifactTypeRegistry typeRegistry = RepositoryUtils.newArtifactTypeRegistry(artifactHandlerManager);

		ArtifactType stereotype = typeRegistry.get(coordinate.getType());
		if (stereotype == null) {
			stereotype = new DefaultArtifactType(coordinate.getType());
		}

		// Create dependency resolution request
		DefaultArtifact aetherArtifact = new DefaultArtifact(coordinate.getGroupId(), coordinate.getArtifactId(),
				coordinate.getClassifier(), null, coordinate.getVersion(), null, stereotype);
		Dependency aetherRoot = new Dependency(aetherArtifact, null);
		List<RemoteRepository> aetherRepositories = RepositoryUtils.toRepos(buildingRequest.getRemoteRepositories());
		DependencyRequest request = new DependencyRequest(new CollectRequest(aetherRoot, aetherRepositories), null);

		// Actually do the dependency resolution
		DependencyResult dependencyResults;
		try {
			dependencyResults = repositorySystem.resolveDependencies(buildingRequest.getRepositorySession(), request);
		} catch (DependencyResolutionException e) {
			throw new DependencyResolverException(e.getMessage(), e);
		}

		// Generate the list of resolved artifacts
		Collection<Artifact> artifacts = new ArrayList<>();
		for (ArtifactResult result : dependencyResults.getArtifactResults()) {
			artifacts.add(toArtifact(result, buildingRequest.getRemoteRepositories()));
			if (includePoms) {
				if (!"pom".equals(result.getArtifact().getExtension())) {

					// Create pom artifact resolution request
					DefaultArtifact aetherPomArtifact = new DefaultArtifact(result.getArtifact().getGroupId(),
							result.getArtifact().getArtifactId(), "pom", result.getArtifact().getVersion());
					ArtifactDescriptorRequest descriptorRequest = new ArtifactDescriptorRequest(aetherPomArtifact,
							aetherRepositories, null);

					ArtifactResult pomResult;
					try {
						ArtifactDescriptorResult descriptorResult = repositorySystem
								.readArtifactDescriptor(buildingRequest.getRepositorySession(), descriptorRequest);

						ArtifactRequest pomRequest = new ArtifactRequest(descriptorResult.getArtifact(),
								aetherRepositories, null);

						pomResult = repositorySystem.resolveArtifact(buildingRequest.getRepositorySession(),
								pomRequest);
					} catch (ArtifactDescriptorException | ArtifactResolutionException e) {
						throw new ArtifactResolverException(e.getMessage(), e);
					}
					artifacts.add(toArtifact(pomResult, buildingRequest.getRemoteRepositories()));
				}
			}
		}

		return artifacts;
	}

	/**
	 * Generate a maven artifact from an aether artifact and a repository reference.
	 */
	private static Artifact toArtifact(ArtifactResult result, List<ArtifactRepository> remoteRepositories) {
		Artifact artifact = RepositoryUtils.toArtifact(result.getArtifact());
		if (result.getRepository() != null) {
			for (ArtifactRepository remoteRepository : remoteRepositories) {
				if (remoteRepository.getId().equals(result.getRepository().getId())) {
					artifact.setRepository(remoteRepository);
				}
			}
		}
		return artifact;
	}
}
