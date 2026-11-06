package com.fieldnote.app;

import org.junit.Test;

import static org.junit.Assert.*;

public final class ShakeDetectorTest {
    @Test public void shakeWorksWithoutAnAssignedAxisOrSign() {
        float[][] directions = {{25, 0, 0}, {-25, 0, 0}, {0, 25, 0}, {0, -25, 0}, {0, 0, 25}, {0, 0, -25}, {15, 15, 15}};
        for (float[] direction : directions) {
            Trace trace = new Trace();
            trace.quiet(500);
            trace.shake(direction);
            assertEquals(1, trace.events);
        }
    }

    @Test public void oneBurstTogglesOnceAndASeparateShakeTogglesAgain() {
        Trace trace = new Trace();
        trace.quiet(500);
        for (int i = 0; i < 12; i++) trace.shake(new float[]{25, 0, 0});
        assertEquals("A continued burst cannot stop the recording it just started", 1, trace.events);
        trace.quiet(1400);
        trace.shake(new float[]{0, -25, 0});
        assertEquals(2, trace.events);
    }

    @Test public void cooldownStillAppliesAfterAQuietPause() {
        Trace trace = new Trace();
        trace.quiet(500);
        trace.shake(new float[]{25, 0, 0});
        trace.quiet(500);
        trace.shake(new float[]{25, 0, 0});
        assertEquals(1, trace.events);
        trace.quiet(1400);
        trace.shake(new float[]{25, 0, 0});
        assertEquals(2, trace.events);
    }

    @Test public void isolatedBumpsAndSingleSampleSpikesDoNotCount() {
        Trace trace = new Trace();
        trace.quiet(500);
        trace.hold(80, 25, 0, 0);
        trace.quiet(900);
        trace.hold(80, 0, -25, 0);
        trace.quiet(500);
        trace.sample(25, 0, 0);
        trace.quiet(100);
        trace.sample(-25, 0, 0);
        trace.quiet(500);
        assertEquals(0, trace.events);
    }

    @Test public void stationaryRotationNoiseAndSustainedAccelerationDoNotCount() {
        Trace trace = new Trace();
        trace.quiet(500);
        for (int i = 0; i < 200; i++) {
            double angle = i * .08;
            trace.sample((float) (9.80665 * Math.sin(angle)), 0, (float) (9.80665 * Math.cos(angle)));
        }
        for (int i = 0; i < 200; i++) trace.sample((float) (3 * Math.sin(i * .2)), 2, 10);
        trace.quiet(500);
        trace.hold(2000, 25, 0, 0);
        trace.quiet(500);
        assertEquals("Synthetic rejection cases do not establish physical walking or drop behavior", 0, trace.events);
    }

    @Test public void initialMotionAndFreeFallCannotArmRecognition() {
        Trace trace = new Trace();
        trace.shake(new float[]{25, 0, 0});
        trace.hold(600, 0, 0, 0);
        trace.shake(new float[]{25, 0, 0});
        assertEquals(0, trace.events);
        trace.quiet(500);
        trace.shake(new float[]{25, 0, 0});
        assertEquals(1, trace.events);
    }

    @Test public void invalidAndReorderedSamplesAbandonAPartialShake() {
        for (int invalid = 0; invalid < 4; invalid++) {
            Trace trace = new Trace();
            trace.quiet(500);
            trace.hold(60, 25, 0, 0);
            trace.quiet(60);
            if (invalid == 0) trace.sample(Float.NaN, 0, 0);
            if (invalid == 1) trace.sample(Float.POSITIVE_INFINITY, 0, 0);
            if (invalid == 2) assertFalse(trace.detector.onAccelerometer(trace.time, 25, 0, 0));
            if (invalid == 3) assertFalse(trace.detector.onAccelerometer(-1, 25, 0, 0));
            trace.hold(60, -25, 0, 0);
            assertEquals(0, trace.events);
            trace.quiet(500);
            trace.shake(new float[]{25, 0, 0});
            assertEquals(1, trace.events);
        }
    }

    @Test public void staleSampleGapAndSessionResetRequireFreshQuiet() {
        for (boolean reset : new boolean[]{false, true}) {
            Trace trace = new Trace();
            trace.quiet(500);
            trace.hold(60, 25, 0, 0);
            trace.quiet(60);
            if (reset) trace.detector.reset(); else trace.time += 300_000_000L;
            trace.hold(60, -25, 0, 0);
            assertEquals(0, trace.events);
            trace.quiet(500);
            trace.shake(new float[]{25, 0, 0});
            assertEquals(1, trace.events);
        }
    }

    @Test public void invalidInputDoesNotShortenTheCooldown() {
        Trace trace = new Trace();
        trace.quiet(500);
        trace.shake(new float[]{25, 0, 0});
        trace.sample(Float.NaN, 0, 0);
        trace.quiet(500);
        trace.shake(new float[]{25, 0, 0});
        assertEquals(1, trace.events);
    }

    private static final class Trace {
        final ShakeDetector detector = new ShakeDetector();
        long time;
        int events;
        void sample(float x, float y, float z) {
            time += 20_000_000L;
            if (detector.onAccelerometer(time, x, y, z)) events++;
        }
        void hold(int milliseconds, float x, float y, float z) {
            for (int i = 0; i < milliseconds; i += 20) sample(x, y, z);
        }
        void quiet(int milliseconds) { hold(milliseconds, 0, 0, 9.80665f); }
        void shake(float[] direction) {
            hold(60, direction[0], direction[1], direction[2]);
            quiet(60);
            hold(60, -direction[0], -direction[1], -direction[2]);
            quiet(60);
        }
    }
}
