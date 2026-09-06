package com.pppp.zhimesh.common.rag.profile;

public final class RouteProfileVectorMath {
    private RouteProfileVectorMath() {
    }

    public static float[] normalize(float[] vector) {
        if (vector == null || vector.length == 0) throw new IllegalArgumentException("Embedding vector is empty");
        double normSquared = 0D;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Embedding vector contains non-finite values");
            normSquared += value * value;
        }
        if (normSquared <= 0D) throw new IllegalArgumentException("Embedding vector norm is zero");
        double norm = Math.sqrt(normSquared);
        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) result[i] = (float) (vector[i] / norm);
        return result;
    }

    public static double dot(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length) return -1D;
        double result = 0D;
        for (int i = 0; i < left.length; i++) result += left[i] * right[i];
        return Math.max(-1D, Math.min(1D, result));
    }
}
