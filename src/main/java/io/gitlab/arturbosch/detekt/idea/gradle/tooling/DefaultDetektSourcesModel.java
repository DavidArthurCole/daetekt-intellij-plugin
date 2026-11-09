package io.gitlab.arturbosch.detekt.idea.gradle.tooling;

import java.io.Serializable;
import java.util.List;

public final class DefaultDetektSourcesModel implements DetektSourcesModel, Serializable {

    private final List<String> directories;

    public DefaultDetektSourcesModel(List<String> directories) {
        this.directories = directories;
    }

    @Override
    public List<String> getDirectories() {
        return directories;
    }
}
