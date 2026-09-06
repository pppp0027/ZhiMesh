package com.pppp.zhimesh.common.rag.profile;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/** Compact, deterministic Float32 codec used by Redis route-profile payloads. */
@Component
public class KnowledgeRouteProfileCodec {

    public String encode(float[] vector) {
        if (vector == null || vector.length == 0) throw new IllegalArgumentException("Profile vector is empty");
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Profile vector contains a non-finite value");
            buffer.putFloat(value);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    public float[] decode(String encoded, int expectedDimension) {
        if (expectedDimension <= 0) throw new IllegalArgumentException("Expected dimension must be positive");
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length != expectedDimension * Float.BYTES) {
            throw new IllegalArgumentException("Profile vector dimension does not match the payload");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[expectedDimension];
        for (int i = 0; i < result.length; i++) {
            result[i] = buffer.getFloat();
            if (!Float.isFinite(result[i])) throw new IllegalArgumentException("Profile vector contains a non-finite value");
        }
        return result;
    }
}
