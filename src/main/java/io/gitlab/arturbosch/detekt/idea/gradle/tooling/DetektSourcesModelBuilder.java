package io.gitlab.arturbosch.detekt.idea.gradle.tooling;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.SourceTask;
import org.jetbrains.plugins.gradle.tooling.AbstractModelBuilderService;
import org.jetbrains.plugins.gradle.tooling.ModelBuilderContext;

import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;

public final class DetektSourcesModelBuilder extends AbstractModelBuilderService {

    private static final String DETEKT_TASK_TYPE = "dev.detekt.gradle.Detekt";

    @Override
    public boolean canBuild(String modelName) {
        return DetektSourcesModel.class.getName().equals(modelName);
    }

    @Override
    public Object buildAll(String modelName, Project project, ModelBuilderContext context) {
        Set<String> directories = new TreeSet<>();
        for (String name : project.getTasks().getNames()) {
            if (!name.startsWith("detekt")) {
                continue;
            }
            Task task = project.getTasks().findByName(name);
            if (task instanceof SourceTask && isDetektTask(task.getClass())) {
                ((SourceTask) task).getSource().visit(details -> {
                    String fileName = details.getName();
                    if (!details.isDirectory() && (fileName.endsWith(".kt") || fileName.endsWith(".kts"))) {
                        directories.add(details.getFile().getParentFile().getAbsolutePath());
                    }
                });
            }
        }
        return directories.isEmpty() ? null : new DefaultDetektSourcesModel(new ArrayList<>(directories));
    }

    private static boolean isDetektTask(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (DETEKT_TASK_TYPE.equals(current.getName())) {
                return true;
            }
        }
        return false;
    }
}
