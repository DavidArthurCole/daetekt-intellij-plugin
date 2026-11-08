package io.gitlab.arturbosch.detekt.idea.gradle.tooling;

import java.io.Serializable;
import java.util.List;

public final class DetektSourcesModel implements Serializable {

    private final List<String> directories;

    public DetektSourcesModel(List<String> directories) {
        this.directories = directories;
    }

    public List<String> getDirectories() {
        return directories;
    }
}
