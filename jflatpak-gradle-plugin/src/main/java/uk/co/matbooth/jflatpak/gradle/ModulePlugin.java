package uk.co.matbooth.jflatpak.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * Generates a Flatpak module from a Gradle project.
 */
public class ModulePlugin implements Plugin<Project> {

	@Override
	public void apply(Project target) {
		target.getTasks().create("module", ModuleTask.class);
	}
}
