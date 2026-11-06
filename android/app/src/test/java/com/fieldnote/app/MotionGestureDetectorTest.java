package com.fieldnote.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class MotionGestureDetectorTest {
    @Test public void forwardPushThenBrakingFiresOnceAndASeparateGestureCanToggleAgain() {
        Trace trace = new Trace(20);
        trace.quiet(600);
        trace.forward();
        assertEquals(1, trace.count(MotionGestureDetector.Gesture.FORWARD_SHAKE));
        trace.quiet(1500);
        trace.forward();
        assertEquals(2, trace.count(MotionGestureDetector.Gesture.FORWARD_SHAKE));
        assertEquals(2, trace.events.size());
    }

    @Test public void backwardsMovementAndItsReturnDoNotCountAsForwardGesture() {
        Trace trace = new Trace(20);
        trace.quiet(600);
        trace.hold(120, 0, 0, 10, 0, 0, 0);
        trace.quiet(40);
        trace.hold(120, 0, 0, -8, 0, 0, 0);
        trace.quiet(600);
        assertTrue(trace.events.isEmpty());
    }

    @Test public void forwardImpulseNeedsOppositeBrakingAndCannotBeOneImpactSample() {
        Trace noBrake = new Trace(20);
        noBrake.quiet(600);
        noBrake.hold(120, 0, 0, -10, 0, 0, 0);
        noBrake.quiet(800);
        assertTrue(noBrake.events.isEmpty());

        Trace impact = new Trace(20);
        impact.quiet(600);
        impact.sample(0, 0, -25, 0, 0, 0);
        impact.sample(0, 0, 25, 0, 0, 0);
        impact.quiet(800);
        assertTrue(impact.events.isEmpty());
    }

    @Test public void sidewaysJostlesWalkingTraceAndSmallNoiseDoNotAct() {
        Trace trace = new Trace(20);
        trace.quiet(600);
        trace.hold(120, 12, 4, -10, 0, 0, 0);
        trace.hold(120, -10, -3, 8, 0, 0, 0);
        trace.quiet(600);
        for (int i = 0; i < 400; i++) {
            double phase = i * .25;
            trace.sample((float) (2 * Math.sin(phase)), (float) (3 * Math.cos(phase)),
                    (float) (2.5 * Math.sin(phase + .5)), .1f, .15f, .2f);
        }
        assertTrue("Synthetic walking/noise is a rejection regression, not physical walking validation", trace.events.isEmpty());
    }

    @Test public void clockwiseAndCounterclockwiseQuickArcsBothTakeOnePhoto() {
        for (int sign : new int[]{-1, 1}) {
            Trace trace = new Trace(20);
            trace.quiet(600);
            trace.twist(sign);
            assertEquals(1, trace.count(MotionGestureDetector.Gesture.TWIST));
            assertEquals(1, trace.events.size());
        }
    }

    @Test public void SmallOrSlowOrExcessiveRotationsDoNotAct() {
        Trace shortArc = new Trace(20);
        shortArc.quiet(600);
        shortArc.hold(80, 0, 0, 0, 0, 0, 4);
        shortArc.quiet(600);
        assertTrue(shortArc.events.isEmpty());
        Trace slow = new Trace(20);
        slow.quiet(600);
        slow.hold(1500, 0, 0, 0, 0, 0, 1.2f);
        slow.quiet(600);
        assertTrue(slow.events.isEmpty());
        Trace large = new Trace(20);
        large.quiet(600);
        large.hold(460, 0, 0, 0, 0, 0, 4);
        large.quiet(600);
        assertTrue(large.events.isEmpty());
    }

    @Test public void alternatingAngularNoiseDoesNotAccumulateIntoAPhoto() {
        Trace trace = new Trace(20);
        trace.quiet(600);
        for (int i = 0; i < 50; i++) trace.sample(0, 0, 0, 0, 0, i % 2 == 0 ? 4 : -4);
        trace.quiet(600);
        assertTrue(trace.events.isEmpty());
    }

    @Test public void rotationAboutOtherAxesAndMixedShakeTwistAreRejected() {
        Trace tilt = new Trace(20);
        tilt.quiet(600);
        tilt.hold(200, 0, 0, 0, 4, 0, .5f);
        tilt.quiet(600);
        assertTrue(tilt.events.isEmpty());

        for (boolean accelerationFirst : new boolean[]{false, true}) {
            Trace mixed = new Trace(20);
            mixed.accelerationFirst = accelerationFirst;
            mixed.quiet(600);
            mixed.hold(120, 0, 0, -10, 0, 0, 4);
            mixed.hold(120, 0, 0, 8, 0, 0, -4);
            mixed.quiet(600);
            assertTrue("Ambiguous simultaneous rotation/translation must not produce either action", mixed.events.isEmpty());
        }
    }

    @Test public void oneSharedCooldownSuppressesReturnStrokeAndTheOtherGesture() {
        Trace trace = new Trace(20);
        trace.quiet(600);
        trace.forward();
        trace.twist(1);
        trace.forward();
        assertEquals(1, trace.events.size());
        trace.quiet(1500);
        trace.twist(-1);
        assertEquals(1, trace.count(MotionGestureDetector.Gesture.FORWARD_SHAKE));
        assertEquals(1, trace.count(MotionGestureDetector.Gesture.TWIST));
    }

    @Test public void equivalentTimedGesturesWorkAt25And50And100HzAndWithJitter() {
        for (int step : new int[]{10, 20, 40}) {
            for (boolean accelerationFirst : new boolean[]{false, true}) {
                Trace trace = new Trace(step);
                trace.accelerationFirst = accelerationFirst;
                trace.quiet(600);
                trace.forward();
                trace.quiet(1500);
                trace.twist(1);
                assertEquals("Forward gesture at step " + step, 1, trace.count(MotionGestureDetector.Gesture.FORWARD_SHAKE));
                assertEquals("Twist at step " + step, 1, trace.count(MotionGestureDetector.Gesture.TWIST));
            }
        }
        Trace jitter = new Trace(20);
        jitter.jitter = true;
        jitter.quiet(600);
        jitter.forward();
        jitter.quiet(1500);
        jitter.twist(-1);
        assertEquals(2, jitter.events.size());
    }

    @Test public void duplicateNonfiniteAndDiscontinuousSamplesCannotFinishAPartialGesture() {
        for (int fault = 0; fault < 3; fault++) {
            Trace trace = new Trace(20);
            trace.quiet(600);
            trace.hold(120, 0, 0, -10, 0, 0, 0);
            if (fault == 0) trace.collect(trace.detector.onLinearAcceleration(trace.time, 0, 0, -10));
            else if (fault == 1) trace.collect(trace.detector.onLinearAcceleration(trace.time + 1, Float.NaN, 0, -10));
            else trace.time += 800_000_000L;
            trace.hold(120, 0, 0, 8, 0, 0, 0);
            trace.quiet(600);
            assertTrue(trace.events.isEmpty());
        }
    }

    @Test public void staleGyroscopeCannotApproveAForwardShake() {
        MotionGestureDetector detector = new MotionGestureDetector(true, true);
        Trace trace = new Trace(20, detector);
        trace.quiet(600);
        trace.time += 300_000_000L;
        for (int i = 0; i < 6; i++)
            trace.collect(detector.onLinearAcceleration(trace.time += 20_000_000L, 0, 0, -10));
        for (int i = 0; i < 6; i++)
            trace.collect(detector.onLinearAcceleration(trace.time += 20_000_000L, 0, 0, 8));
        assertTrue(trace.events.isEmpty());
    }

    @Test public void resetDropsPartialMotionAndRequiresFreshQuietRearming() {
        Trace trace = new Trace(20);
        trace.quiet(600);
        trace.hold(120, 0, 0, -10, 0, 0, 0);
        trace.detector.reset();
        trace.hold(120, 0, 0, 8, 0, 0, 0);
        assertTrue(trace.events.isEmpty());
        trace.quiet(600);
        trace.forward();
        assertEquals(1, trace.events.size());
    }

    @Test public void gravityFallbackIgnoresRestAndSlowTiltButRetainsDeliberateForwardMotion() {
        Trace raw = new Trace(20);
        raw.rawAccelerometer = true;
        raw.quiet(2000);
        assertTrue(raw.events.isEmpty());
        raw.forward();
        assertEquals(1, raw.count(MotionGestureDetector.Gesture.FORWARD_SHAKE));

        MotionGestureDetector tilt = new MotionGestureDetector(true, true);
        long time = 1_000_000_000L;
        for (int i = 0; i < 250; i++) {
            time += 20_000_000L;
            double angle = Math.max(0, i - 100) * .005;
            assertEquals(MotionGestureDetector.Gesture.NONE, tilt.onGyroscope(time, .25f, 0, 0));
            assertEquals(MotionGestureDetector.Gesture.NONE, tilt.onAccelerometer(time, 0,
                    (float) (9.81 * Math.sin(angle)), (float) (9.81 * Math.cos(angle))));
        }
    }

    @Test public void availableSingleSensorModesStayWithinTheirCapabilities() {
        Trace accelerationOnly = new Trace(20, new MotionGestureDetector(true, false));
        accelerationOnly.feedGyroscope = false;
        accelerationOnly.quiet(600);
        accelerationOnly.forward();
        assertEquals(1, accelerationOnly.count(MotionGestureDetector.Gesture.FORWARD_SHAKE));
        Trace gyroOnly = new Trace(20, new MotionGestureDetector(false, true));
        gyroOnly.feedAcceleration = false;
        gyroOnly.quiet(600);
        gyroOnly.twist(1);
        assertEquals(1, gyroOnly.count(MotionGestureDetector.Gesture.TWIST));
    }

    private static final class Trace {
        final MotionGestureDetector detector;
        final int step;
        final List<MotionGestureDetector.Gesture> events = new ArrayList<>();
        long time = 1_000_000_000L;
        int samples;
        boolean accelerationFirst, jitter, rawAccelerometer;
        boolean feedAcceleration = true, feedGyroscope = true;

        Trace(int step) { this(step, new MotionGestureDetector(true, true)); }
        Trace(int step, MotionGestureDetector detector) { this.step = step; this.detector = detector; }
        void quiet(int ms) { hold(ms, 0, 0, 0, 0, 0, 0); }
        void forward() {
            hold(120, 0, 0, -10, 0, 0, 0);
            quiet(40);
            hold(120, 0, 0, 8, 0, 0, 0);
            quiet(80);
        }
        void twist(int sign) { hold(200, 0, 0, 0, 0, 0, sign * 4); quiet(80); }
        void hold(int ms, float ax, float ay, float az, float gx, float gy, float gz) {
            long end = time + ms * 1_000_000L;
            while (time < end) sample(ax, ay, az, gx, gy, gz);
        }
        void sample(float ax, float ay, float az, float gx, float gy, float gz) {
            time += (step + (jitter ? (++samples % 2 == 0 ? 5 : -5) : 0)) * 1_000_000L;
            if (accelerationFirst) acceleration(ax, ay, az);
            if (feedGyroscope) collect(detector.onGyroscope(time, gx, gy, gz));
            if (!accelerationFirst) acceleration(ax, ay, az);
        }
        void acceleration(float x, float y, float z) {
            if (!feedAcceleration) return;
            collect(rawAccelerometer ? detector.onAccelerometer(time, x, y, z + 9.81f)
                    : detector.onLinearAcceleration(time, x, y, z));
        }
        void collect(MotionGestureDetector.Gesture event) { if (event != MotionGestureDetector.Gesture.NONE) events.add(event); }
        int count(MotionGestureDetector.Gesture gesture) { int count = 0; for (MotionGestureDetector.Gesture event : events) if (event == gesture) count++; return count; }
    }
}
