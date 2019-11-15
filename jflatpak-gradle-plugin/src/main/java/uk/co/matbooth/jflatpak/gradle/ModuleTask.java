package uk.co.matbooth.jflatpak.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;

public class ModuleTask extends DefaultTask {

	@TaskAction
	public void action() {
		System.out.println("Yo");
	}
}
