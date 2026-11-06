package com.fieldnote.app;

/** Small, platform-independent capture-time and orientation calculations. */
final class CameraMath {
    private CameraMath() {}

    static int jpegRotation(int sensorOrientation, int displayDegrees, boolean frontFacing) {
        return (sensorOrientation + (frontFacing ? displayDegrees : -displayDegrees) + 360) % 360;
    }

    static int deviceCaptureRotation(int sensorOrientation, int deviceDegrees, boolean frontFacing) {
        int quadrant = ((deviceDegrees + 45) / 90 * 90) % 360;
        return (sensorOrientation + (frontFacing ? -quadrant : quadrant) + 360) % 360;
    }

    static long sensorWallTime(long sensorNanos, boolean realtime, long wallNow,
                               long elapsedNowNanos) {
        if (!realtime || sensorNanos <= 0) return wallNow;
        long ageNanos = elapsedNowNanos - sensorNanos;
        // Invalid or incompatible vendor clocks must not produce invented capture dates.
        if (ageNanos < 0 || ageNanos > 30_000_000_000L) return wallNow;
        return wallNow - ageNanos / 1_000_000L;
    }

    static long endTime(long startWall, long startElapsed, long endElapsed) {
        return startWall + Math.max(0, endElapsed - startElapsed);
    }
}
