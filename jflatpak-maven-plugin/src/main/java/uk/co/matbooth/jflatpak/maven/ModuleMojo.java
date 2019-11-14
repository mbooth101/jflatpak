package uk.co.matbooth.jflatpak.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;

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

/**
 * Generates a Flatpak module from a Maven project.
 */
@Mojo(name = "module", aggregator = true, requiresProject = true)
public class ModuleMojo extends AbstractMojo {

	/**
	 * Space delimited list of phases to execute when building the project; the
	 * default is <code>verify</code>.
	 */
	@Parameter(defaultValue = "verify", property = "jflatpak.phases", required = true)
	private String phases;

	/**
	 * Name of the file (without extension) to which the output will be saved.
	 */
	@Parameter(defaultValue = "maven-module", property = "jflatpak.outputFile", required = true)
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

	/**
	 * Location of the local maven repository inside the Flatpak build root.
	 */
	@Parameter(defaultValue = ".m2/repository", property = "jflatpak.localRepo", required = true)
	private String localRepo;

	@Parameter(defaultValue = "${project}", readonly = true)
	private MavenProject project;

	@Parameter(defaultValue = "${session}", readonly = true)
	private MavenSession session;

	@Parameter(defaultValue = "${reactorProjects}", readonly = true)
	private List<MavenProject> reactorProjects;

	@Component
	private CustomResolver resolver;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		Path localRepoPath = Paths.get(project.getBuild().getDirectory(), localRepo);

		// Best way to know all the dependencies and plug-in dependencies we need to
		// build offline is to just invoke maven and build the project, this will force
		// the resolution of all required artifacts
		Invoker invoker = new DefaultInvoker();
		InvocationRequest request = new DefaultInvocationRequest();
		request.setPomFile(project.getFile());
		request.setGoals(Arrays.asList(phases.split("\\s+")));
		request.setBatchMode(true);
		request.setMavenOpts("-Dmaven.repo.local=" + localRepoPath.toString());
		try {
			InvocationResult result = invoker.execute(request);
			if (result.getExitCode() != 0) {
				throw new MojoFailureException("Maven invocation did not complete successfully");
			}
		} catch (MavenInvocationException e) {
			throw new MojoFailureException(e.getMessage(), e);
		}

		// Find all the artifacts that have been resolved into the local repository
		ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
		Set<ArtifactRepository> repos = new HashSet<>();
		for (MavenProject reactorProject : reactorProjects) {
			repos.addAll(reactorProject.getRemoteArtifactRepositories());
			repos.addAll(reactorProject.getPluginArtifactRepositories());
		}
		buildingRequest.setRemoteRepositories(new ArrayList<ArtifactRepository>(repos));
		List<Artifact> artifacts;
		try (Stream<Path> paths = Files.walk(localRepoPath, Integer.MAX_VALUE)) {
			artifacts = paths.filter(path -> !Files.isDirectory(path))
					.filter(path -> !path.getFileName().toString().equals("_remote.repositories")
							&& !path.getFileName().toString().endsWith("sha1"))
					.map(path -> visitPath(buildingRequest, localRepoPath, path)).filter(a -> a != null).sorted()
					.collect(Collectors.toList());
		} catch (IOException e) {
			throw new MojoFailureException(e.getMessage(), e);
		}

		// Create data model from resolved artifacts
		Environment env = new Environment();
		env.put("JAVA_HOME", "/usr/lib/sdk/openjdk11/jvm/openjdk-11");
		env.put("PATH", "/app/bin:/usr/bin:/usr/lib/sdk/openjdk11/bin");
		env.put("MAVEN_OPTS", "-Dmaven.repo.local=" + localRepo);
		BuildOptions buildOptions = new BuildOptions();
		buildOptions.setEnv(env);
		Module module = new Module();
		if (project.getName() != null && !project.getName().isEmpty()) {
			module.setName(project.getName().replace(' ', '_').replace('/', '_'));
		} else {
			module.setName(project.getArtifactId());
		}
		module.setBuildsystem(BuildSystemType.SIMPLE);
		module.setBuildOptions(buildOptions);
		module.getBuildCommands().add("mvn -V -B -o " + phases);

		// Add sources for all dependencies
		for (Artifact artifact : artifacts) {
			ArtifactRepositoryLayout layout = artifact.getRepository().getLayout();
			File layoutPath = new File(layout.pathOf(artifact));
			File destPath = new File(localRepo, layoutPath.getParent());
			String url = artifact.getRepository().getUrl();
			Source source = new Source();
			source.setType(SourceType.FILE);
			Path shaPath = localRepoPath.resolve(layoutPath.toString().concat(".sha1"));
			source.setSha1(getDigest(shaPath));
			source.setUrl(url + "/" + layoutPath.toString());
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
					default:
						getLog().warn("Unsupported SCM type '" + parts[1].toLowerCase()
								+ "', project source will not be added");
						break;
					}
				}
			}
		}

		// Write manifest model to disk
		try {
			writeManifestFile(module);
		} catch (IOException e) {
			throw new MojoExecutionException("Could write output file: " + e.getMessage(), e);
		}
	}

	private Artifact visitPath(ProjectBuildingRequest buildingRequest, Path localRepoPath, Path path) {
		Path artifactPath = path.subpath(localRepoPath.getNameCount(), path.getNameCount());
		String version = artifactPath.getName(artifactPath.getNameCount() - 2).toString();
		String artifactId = artifactPath.getName(artifactPath.getNameCount() - 3).toString();
		String groupId = "";
		for (int i = 0; i < artifactPath.getNameCount() - 3; i++) {
			if (groupId.length() == 0) {
				groupId = artifactPath.getName(i).toString();
			} else {
				groupId = groupId + "." + artifactPath.getName(i).toString();
			}
		}
		Pattern pattern = Pattern.compile("^[a-zA-Z_0-9-.]*-" + version + "(-([a-zA-Z_0-9-.]*))?\\.(.*)$");
		Matcher matcher = pattern.matcher(artifactPath.getFileName().toString());
		matcher.find();
		String classifier = matcher.group(2);
		String extension = matcher.group(3);
		Artifact artifact = null;
		try {
			artifact = resolver.resolveDependency(buildingRequest, groupId, artifactId, classifier, extension, version);
		} catch (MojoExecutionException e) {
			getLog().error("Failure to resolve artifact: " + e.getMessage(), e);
		}
		return artifact;
	}

	private String getDigest(Path path) throws MojoExecutionException {
		List<String> lines;
		try {
			lines = Files.readAllLines(path);
		} catch (IOException e) {
			throw new MojoExecutionException("Unable to read digest from: " + path, e);
		}
		if (lines.size() > 0 && lines.get(0).length() > 0) {
			return lines.get(0).split("\\s")[0];
		}
		throw new MojoExecutionException("No digest was read from: " + path);
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
