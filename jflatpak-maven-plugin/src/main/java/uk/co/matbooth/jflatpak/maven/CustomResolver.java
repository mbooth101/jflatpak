package uk.co.matbooth.jflatpak.maven;

import java.util.List;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.internal.impl.DefaultArtifactResolver;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

/**
 * A custom artifact resolver that returns artifacts complete with information
 * about from which repositories the artifacts were resolved. The artifacts
 * returned from the standard {@link DefaultArtifactResolver} have the
 * repository information omitted, so we implement it here such that the results
 * contain the information we need.
 */
@Component(role = CustomResolver.class)
class CustomResolver {

	@Requirement
	private RepositorySystem repositorySystem;

	/**
	 * Resolves an artifact based on the given maven artifact coordinates.
	 * 
	 * @return a fully-formed maven artifact
	 * @throws MojoExecutionException if the artifact could not be resolved for any
	 *                                reason
	 */
	public Artifact resolveDependency(ProjectBuildingRequest buildingRequest, String groupId, String artifactId,
			String classifier, String extension, String version) throws MojoExecutionException {

		DefaultArtifact aetherArtifact = new DefaultArtifact(groupId, artifactId,
				(classifier == null ? "" : classifier), extension, version);
		List<RemoteRepository> aetherRepos = RepositoryUtils.toRepos(buildingRequest.getRemoteRepositories());

		// Request artifact descriptor
		ArtifactDescriptorRequest descriptorRequest = new ArtifactDescriptorRequest(aetherArtifact, aetherRepos, null);
		ArtifactDescriptorResult descriptorResult;
		try {
			descriptorResult = repositorySystem.readArtifactDescriptor(buildingRequest.getRepositorySession(),
					descriptorRequest);
		} catch (ArtifactDescriptorException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}

		// Request artifact
		ArtifactRequest request = new ArtifactRequest(descriptorResult.getArtifact(), aetherRepos, null);
		ArtifactResult result;
		try {
			result = repositorySystem.resolveArtifact(buildingRequest.getRepositorySession(), request);
		} catch (ArtifactResolutionException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}

		return toArtifact(result, buildingRequest.getRemoteRepositories());
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
