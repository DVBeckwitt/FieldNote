package com.fieldnote.app;

import org.junit.Test;

import static com.fieldnote.app.GestureGuideProgress.Haptic.APPROACH;
import static com.fieldnote.app.GestureGuideProgress.Haptic.NEAR;
import static com.fieldnote.app.GestureGuideProgress.Haptic.RETREAT;
import static com.fieldnote.app.RecordingGesture.Direction.*;
import static org.junit.Assert.*;

public class GestureGuideProgressTest {
    @Test public void screenGuideUses112ForCardinalProgressRatherThanTheOld64() {
        GestureGuideProgress guide = new GestureGuideProgress(RecordingGesture.ACTIVATION_DISTANCE_DP);
        float[][] axes = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        RecordingGesture.Direction[] expected = {LEFT, RIGHT, UP, DOWN};
        for (int i = 0; i < axes.length; i++) {
            guide.reset();
            assertState(guide.update(axes[i][0] * 64, axes[i][1] * 64, NONE, 0),
                    expected[i], 64f / 112, 2, APPROACH);
            assertState(guide.update(axes[i][0] * 111.99f, axes[i][1] * 111.99f, NONE, 80),
                    expected[i], 111.99f / 112, 3, NEAR);
            assertState(guide.update(axes[i][0] * 112, axes[i][1] * 112, NONE, 160),
                    expected[i], 1, 3, GestureGuideProgress.Haptic.NONE);
        }
    }

    @Test public void screenGuideUses112ForRadialMarkerProgress() {
        GestureGuideProgress guide = new GestureGuideProgress(RecordingGesture.ACTIVATION_DISTANCE_DP);
        assertState(guide.update(38.4f, -51.2f, NONE, 0), MARKER, 64f / 112, 2, APPROACH);
        assertState(guide.update(48, -64, NONE, 80), MARKER, 80f / 112, 2, GestureGuideProgress.Haptic.NONE);
        assertState(guide.update(60, -80, NONE, 160), MARKER, 100f / 112, 3, NEAR);
        assertState(guide.update(80, -80, NONE, 240), MARKER, 1, 3, GestureGuideProgress.Haptic.NONE);
    }

    @Test public void approachCuesOccurOnlyWhenEnteringHigherLevels() {
        GestureGuideProgress guide = new GestureGuideProgress(100);
        assertEquals(GestureGuideProgress.Haptic.NONE, guide.update(1, 0, NONE, 0).haptic);
        assertEquals(GestureGuideProgress.Haptic.NONE, guide.update(0, 1, NONE, 0).haptic);
        assertState(guide.update(24, 0, NONE, 0), RIGHT, .24f, 0, GestureGuideProgress.Haptic.NONE);
        assertState(guide.update(25, 0, NONE, 10), RIGHT, .25f, 1, APPROACH);
        assertState(guide.update(49, 0, NONE, 90), RIGHT, .49f, 1, GestureGuideProgress.Haptic.NONE);
        assertState(guide.update(50, 0, NONE, 90), RIGHT, .5f, 2, APPROACH);
        assertState(guide.update(80, 0, NONE, 170), RIGHT, .8f, 3, NEAR);
        assertState(guide.update(200, 0, NONE, 250), RIGHT, 1, 3, GestureGuideProgress.Haptic.NONE);
    }

    @Test public void retractingAndLeavingACandidateCueRetreat() {
        GestureGuideProgress guide = new GestureGuideProgress(100);
        assertEquals(NEAR, guide.update(-90, 0, NONE, 0).haptic);
        assertState(guide.update(-79, 0, NONE, 80), LEFT, .79f, 2, RETREAT);
        assertState(guide.update(-49, 0, NONE, 160), LEFT, .49f, 1, RETREAT);
        assertState(guide.update(-24, 0, NONE, 240), LEFT, .24f, 0, RETREAT);
        assertState(guide.update(0, 0, NONE, 320), NONE, 0, 0, GestureGuideProgress.Haptic.NONE);
        assertEquals(GestureGuideProgress.Haptic.NONE, guide.update(0, 0, NONE, 400).haptic);
    }

    @Test public void hapticsAreThrottledAndNotDeferredToStationaryFrames() {
        GestureGuideProgress guide = new GestureGuideProgress(100);
        assertEquals(APPROACH, guide.update(25, 0, NONE, 0).haptic);
        assertEquals(GestureGuideProgress.Haptic.NONE, guide.update(50, 0, NONE, 40).haptic);
        assertEquals(GestureGuideProgress.Haptic.NONE, guide.update(80, 0, NONE, 79).haptic);
        assertEquals(GestureGuideProgress.Haptic.NONE, guide.update(80, 0, NONE, 80).haptic);
        assertEquals(RETREAT, guide.update(49, 0, NONE, 80).haptic);
        assertEquals(GestureGuideProgress.Haptic.NONE, guide.update(24, 0, NONE, 159).haptic);
        assertEquals(GestureGuideProgress.Haptic.NONE, guide.update(0, 0, NONE, 160).haptic);
    }

    @Test public void diagonalMovementHasNoCandidateUntilAnAxisDominates() {
        GestureGuideProgress guide = new GestureGuideProgress(100);
        assertState(guide.update(60, 60, NONE, 0), NONE, 0, 0, GestureGuideProgress.Haptic.NONE);
        assertState(guide.update(80, 65, NONE, 80), NONE, 0, 0, GestureGuideProgress.Haptic.NONE);
        assertState(guide.update(80, 64, NONE, 160), RIGHT, .8f, 3, NEAR);
        assertState(guide.update(80, 80, NONE, 240), NONE, 0, 0, RETREAT);
    }

    @Test public void progressTracksAllFourDirectionsWithoutLatching() {
        GestureGuideProgress guide = new GestureGuideProgress(100);
        assertState(guide.update(0, -50, NONE, 0), UP, .5f, 2, APPROACH);
        assertState(guide.update(0, 50, NONE, 80), DOWN, .5f, 2, RETREAT);
        assertState(guide.update(-50, 0, NONE, 160), LEFT, .5f, 2, RETREAT);
        assertState(guide.update(50, 0, NONE, 240), RIGHT, .5f, 2, RETREAT);
    }

    @Test public void activationSuppliesFullProgressWithoutAnExtraHaptic() {
        GestureGuideProgress guide = new GestureGuideProgress(100);
        guide.update(0, -50, NONE, 0);
        assertState(guide.update(0, -100, UP, 80), UP, 1, 3, GestureGuideProgress.Haptic.NONE);
        assertState(guide.update(0, 0, UP, 160), UP, 1, 3, GestureGuideProgress.Haptic.NONE);
        assertState(guide.update(200, 0, UP, 240), UP, 1, 3, GestureGuideProgress.Haptic.NONE);
        guide.reset();
        assertState(guide.update(0, 80, NONE, 241), DOWN, .8f, 3, NEAR);
    }

    @Test public void markerProgressAndCueLevelsUseRadialDistance() {
        GestureGuideProgress guide = new GestureGuideProgress(100);
        assertState(guide.update(15, -20, NONE, 0), MARKER, .25f, 1, APPROACH);
        assertState(guide.update(30, -40, NONE, 80), MARKER, .5f, 2, APPROACH);
        assertState(guide.update(48, -64, NONE, 160), MARKER, .8f, 3, NEAR);
        assertState(guide.update(60, -80, NONE, 240), MARKER, 1, 3, GestureGuideProgress.Haptic.NONE);
        assertState(guide.update(120, -160, NONE, 320), MARKER, 1, 3, GestureGuideProgress.Haptic.NONE);
    }

    @Test public void guideAndGestureShareMarkerBoundariesDuringApproach() {
        GestureGuideProgress guide = new GestureGuideProgress(100);
        float[][] points = {{30, -15}, {15, -30}, {20, -20}, {30, -14.99f}, {14.99f, -30},
                {0, -20}, {20, 0}, {-20, -20}, {20, 20}, {0, 0}};
        RecordingGesture.Direction[] expected = {MARKER, MARKER, MARKER, RIGHT, UP, UP, RIGHT, NONE, NONE, NONE};
        for (int i = 0; i < points.length; i++) {
            guide.reset();
            float dx = points[i][0], dy = points[i][1];
            GestureGuideProgress.State state = guide.update(dx, dy, NONE, 0);
            assertEquals("Guide candidate at case " + i, expected[i], state.direction);
            assertEquals("Shared gesture candidate at case " + i,
                    RecordingGesture.candidateDirection(dx, dy), state.direction);
        }
    }

    @Test public void markerActivationSuppressesExtraCuesAndResetAllowsNextApproach() {
        GestureGuideProgress guide = new GestureGuideProgress(100);
        assertState(guide.update(48, -64, NONE, 0), MARKER, .8f, 3, NEAR);
        assertState(guide.update(60, -80, MARKER, 80), MARKER, 1, 3, GestureGuideProgress.Haptic.NONE);
        assertState(guide.update(-100, 0, MARKER, 160), MARKER, 1, 3, GestureGuideProgress.Haptic.NONE);
        guide.reset();
        assertState(guide.update(0, 0, NONE, 161), NONE, 0, 0, GestureGuideProgress.Haptic.NONE);
        assertState(guide.update(15, -20, NONE, 162), MARKER, .25f, 1, APPROACH);
    }

    @Test public void resetBeginsAFreshHapticInterval() {
        GestureGuideProgress guide = new GestureGuideProgress(100);
        assertEquals(APPROACH, guide.update(25, 0, NONE, 1000).haptic);
        guide.reset();
        assertState(guide.update(0, 0, NONE, 1001), NONE, 0, 0, GestureGuideProgress.Haptic.NONE);
        assertEquals(APPROACH, guide.update(25, 0, NONE, 1002).haptic);
    }

    @Test public void invalidDeltasDoNotProduceInvalidProgress() {
        GestureGuideProgress guide = new GestureGuideProgress(100);
        assertState(guide.update(Float.NaN, 0, NONE, 0), NONE, 0, 0, GestureGuideProgress.Haptic.NONE);
        assertState(guide.update(0, Float.POSITIVE_INFINITY, NONE, 80), NONE, 0, 0, GestureGuideProgress.Haptic.NONE);
    }

    @Test public void thresholdMustBeFiniteAndPositive() {
        for (float invalid : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new GestureGuideProgress(invalid));
        }
    }

    private static void assertState(GestureGuideProgress.State state, RecordingGesture.Direction direction,
                                    float progress, int level, GestureGuideProgress.Haptic haptic) {
        assertEquals(direction, state.direction);
        assertEquals(progress, state.progress, .0001f);
        assertEquals(level, state.level);
        assertEquals(haptic, state.haptic);
    }
}
