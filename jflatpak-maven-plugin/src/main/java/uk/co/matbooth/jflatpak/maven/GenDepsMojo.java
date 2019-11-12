package uk.co.matbooth.jflatpak.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.codec.binary.Hex;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.transfer.dependencies.DefaultDependableCoordinate;
import org.apache.maven.shared.transfer.dependencies.resolve.DependencyResolverException;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationIntrospector;

import uk.co.matbooth.jflatpak.manifest.BuildOptions;
import uk.co.matbooth.jflatpak.manifest.BuildSystemType;
import uk.co.matbooth.jflatpak.manifest.Environment;
import uk.co.matbooth.jflatpak.manifest.Module;
import uk.co.matbooth.jflatpak.manifest.Source;
import uk.co.matbooth.jflatpak.manifest.SourceType;

@Mojo(name = "gen-deps", aggregator = true, requiresProject = true)
public class GenDepsMojo extends AbstractMojo {

	/**
	 * Name of the file (without extension) to which the output will be saved.
	 */
	@Parameter(defaultValue = "maven-deps", property = "jflatpak.outputFile", required = true)
	private String outputFile;

	/**
	 * Format of the output file. Can be <code>json</code> or <code>yaml</code>; the
	 * default is <code>json</code>.
	 */
	@Parameter(defaultValue = "json", property = "jflatpak.outputType", required = true)
	private String outputFormat;

	/**
	 * Directory in which to save the output.
	 */
	@Parameter(defaultValue = ".", property = "jflatpak.outputDir", required = true)
	private File outputDir;

	@Parameter(defaultValue = "${reactorProjects}", readonly = true)
	private List<MavenProject> reactorProjects;

	@Parameter(defaultValue = "${session}", readonly = true)
	private MavenSession session;

	@Parameter(defaultValue = "${project}", readonly = true)
	private MavenProject project;

	@Component
	private CustomResolver depResolver;

	@Component
	private ArtifactResolver artResolver;

	public void execute() throws MojoExecutionException, MojoFailureException {

		Set<Artifact> artifacts = new HashSet<>();
		try {
			ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(
					session.getProjectBuildingRequest());

			for (MavenProject reactorProject : reactorProjects) {
				buildingRequest.setRemoteRepositories(reactorProject.getRemoteArtifactRepositories());
				for (Dependency dep : reactorProject.getDependencies()) {
					DefaultDependableCoordinate coordinate = new DefaultDependableCoordinate();
					coordinate.setGroupId(dep.getGroupId());
					coordinate.setArtifactId(dep.getArtifactId());
					coordinate.setVersion(dep.getVersion());
					if (dep.getType() != null && !dep.getType().isEmpty()) {
						coordinate.setType(dep.getType());
					}

					getLog().debug("Resolving " + coordinate + " with transitive dependencies");
					Iterable<Artifact> results = depResolver.resolveDependency(buildingRequest, coordinate, true);
					for (Artifact artifact : results) {
						if (artifact.getRepository() != null) {
							artifacts.add(artifact);
						}
					}
				}
			}
		} catch (DependencyResolverException | ArtifactResolverException e) {
			throw new MojoExecutionException("Could not resolve artifact: " + e.getMessage(), e);
		}

		// Create data model from resolved artifacts
		Environment env = new Environment();
		env.put("JAVA_HOME", "/usr/lib/sdk/openjdk11/jvm/openjdk-11");
		env.put("PATH", "/app/bin:/usr/bin:/usr/lib/sdk/openjdk11/bin");
		BuildOptions buildOptions = new BuildOptions();
		buildOptions.setEnv(env);
		Module module = new Module();
		if (project.getName() != null && !project.getName().isEmpty()) {
			module.setName(project.getName());
		} else {
			module.setName(project.getArtifactId());
		}
		module.setBuildsystem(BuildSystemType.SIMPLE);
		module.setBuildOptions(buildOptions);
		module.getBuildCommands().add("mvn -V -B -o clean verify");

		// Add sources for all dependencies
		for (Artifact artifact : artifacts) {
			getLog().debug("Resolved: " + artifact.toString());
			ArtifactRepositoryLayout layout = artifact.getRepository().getLayout();
			File layoutPath = new File(layout.pathOf(artifact));
			File destPath = new File(".m2/repository", layoutPath.getParent());
			String url = artifact.getRepository().getUrl();
			Source source = new Source();
			source.setType(SourceType.FILE);
			source.setUrl(url + "/" + layoutPath.toString());
			source.setSha256(getDigest(artifact.getFile()));
			source.setDest(destPath.toString());
			module.getSources().add(source);
		}

		// Add source for main project if SCM information is given
		if (project.getScm() != null) {
			String conn = project.getScm().getConnection();
			if (conn != null && !conn.isEmpty()) {
				String[] parts = conn.split(":", 3);
				if (parts.length == 3 && "scm".equals(parts[0])) {
					Source projSource = new Source();
					switch (parts[1].toLowerCase()) {
					case "git":
						projSource.setType(SourceType.GIT);
						projSource.setUrl(parts[2]);
						module.getSources().add(projSource);
						break;
					case "svn":
						projSource.setType(SourceType.SVN);
						projSource.setUrl(parts[2]);
						module.getSources().add(projSource);
						break;
					case "bzr":
						projSource.setType(SourceType.BZR);
						projSource.setUrl(parts[2]);
						module.getSources().add(projSource);
						break;
					}
				}
			}
		}

		try {
			// Write manifest model to disk
			writeManifestFile(module);
		} catch (IOException e) {
			throw new MojoExecutionException("Could write output file: " + e.getMessage(), e);
		}
	}

	private String getDigest(File file) throws MojoExecutionException, MojoFailureException {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			try (InputStream is = Files.newInputStream(file.toPath());
					DigestInputStream dis = new DigestInputStream(is, md)) {
				while (dis.read() != -1) {
				}
				return Hex.encodeHexString(md.digest());
			}
		} catch (IOException | NoSuchAlgorithmException e) {
			throw new MojoExecutionException("Unable to get digest of " + file + ": " + e.getMessage(), e);
		}
	}

	private void writeManifestFile(Object model) throws IOException {
		ObjectMapper mapper;
		switch (outputFormat) {
		case "yaml":
			mapper = new ObjectMapper(new YAMLFactory());
			break;
		default:
			// The default is "json"
			mapper = new ObjectMapper();
			break;
		}
		mapper.setAnnotationIntrospector(new JaxbAnnotationIntrospector(mapper.getTypeFactory()));
		mapper.setSerializationInclusion(Include.NON_NULL);

		File output = new File(outputDir, outputFile + "." + outputFormat);
		getLog().info("Writing to " + output);
		mapper.writerWithDefaultPrettyPrinter().writeValue(output, model);
	}
}
