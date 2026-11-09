package io.gitlab.arturbosch.detekt.idea.gradle.tooling;

import java.io.Serializable;
import java.util.List;

public final class DefaultDetektSourcesModel implements DetektSourcesModel, Serializable {

    private final List<String> directories;
    private final List<String> roots;

    public DefaultDetektSourcesModel(List<String> directories, List<String> roots) {
        this.directories = directories;
        this.roots = roots;
    }

    @Override
    public List<String> getDirectories() {
        return directories;
    }

    @Override
    public List<String> getRoots() {
        return roots;
    }
}
