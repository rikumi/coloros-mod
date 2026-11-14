package com.rikumi.colorosmod.hooks;

import android.graphics.Path;
import android.graphics.PathIterator;
import android.os.Build;

final class ContourMaskCache {
    private Path renderedPath;
    private Path checkedPath;
    private int checkedGeneration;
    private int width, height;
    private float stroke;
    private final float[] before = new float[8], after = new float[8];

    boolean matches(Path path, int width, int height, float stroke) {
        if (Build.VERSION.SDK_INT < 34 || renderedPath == null
                || this.width != width || this.height != height || this.stroke != stroke
                || renderedPath.getFillType() != path.getFillType()) return false;
        if(checkedPath==path && checkedGeneration==path.getGenerationId())return true;
        PathIterator oldShape = renderedPath.getPathIterator();
        PathIterator newShape = path.getPathIterator();
        while (true) {
            int verb = oldShape.next(before, 0);
            if (verb != newShape.next(after, 0)) return false;
            int count;
            switch (verb) {
                case PathIterator.VERB_DONE:
                    checkedPath=path; checkedGeneration=path.getGenerationId(); return true;
                case PathIterator.VERB_CLOSE: count = 0; break;
                case PathIterator.VERB_MOVE: count = 2; break;
                case PathIterator.VERB_LINE: count = 4; break;
                case PathIterator.VERB_QUAD: count = 6; break;
                case PathIterator.VERB_CONIC: count = 7; break; // Includes the weight at index 6.
                case PathIterator.VERB_CUBIC: count = 8; break;
                default: return false;
            }
            for (int i = 0; i < count; i++) if (before[i] != after[i]) return false;
        }
    }

    void remember(Path path, int width, int height, float stroke) {
        if (renderedPath == null) renderedPath = new Path(path);
        else renderedPath.set(path);
        this.width = width;
        this.height = height;
        this.stroke = stroke;
        checkedPath=path;
        checkedGeneration=path.getGenerationId();
    }
}
