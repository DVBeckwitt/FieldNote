package com.fieldnote.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecordingGestureTest {
    @Test public void screenDefaultsRequire112ForEachCardinalAction() {
        assertEquals(112f, RecordingGesture.ACTIVATION_DISTANCE_DP, 0f);
        assertEquals(28f, RecordingGesture.HOME_RADIUS_DP, 0f);
        RecordingGesture gesture = new RecordingGesture(
                RecordingGesture.ACTIVATION_DISTANCE_DP, RecordingGesture.HOME_RADIUS_DP);
        float[][] axes = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        RecordingGesture.Direction[] expected = {RecordingGesture.Direction.LEFT, RecordingGesture.Direction.RIGHT,
                RecordingGesture.Direction.UP, RecordingGesture.Direction.DOWN};
        for (int i = 0; i < axes.length; i++) {
            gesture.onDown(0, 0);
            assertEquals(RecordingGesture.Direction.NONE, gesture.update(axes[i][0] * 64, axes[i][1] * 64));
            assertEquals(RecordingGesture.Direction.NONE, gesture.update(axes[i][0] * 111.99f, axes[i][1] * 111.99f));
            assertTrue(gesture.isArmed());
            assertEquals(expected[i], gesture.update(axes[i][0] * 112, axes[i][1] * 112));
            assertFalse(gesture.onUp());
        }
    }

    @Test public void screenMarkerRequiresTheNew112RadialDistance() {
        RecordingGesture gesture = new RecordingGesture(
                RecordingGesture.ACTIVATION_DISTANCE_DP, RecordingGesture.HOME_RADIUS_DP);
        gesture.onDown(0, 0);
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(38.4f, -51.2f));
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(79, -79));
        assertEquals(RecordingGesture.Direction.MARKER, gesture.update(80, -80));
        assertFalse(gesture.isArmed());
    }

    @Test public void explicitHomeRemains28WhenActivationDistanceGrowsTo112() {
        RecordingGesture gesture = new RecordingGesture(
                RecordingGesture.ACTIVATION_DISTANCE_DP, RecordingGesture.HOME_RADIUS_DP);
        gesture.onDownAt(300, 400, 329, 400);
        assertFalse(gesture.isCentered());
        assertFalse(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(412, 400));
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(328, 400));
        assertTrue(gesture.isCentered());
        assertTrue(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.RIGHT, gesture.update(412, 400));
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(328.01f, 400));
        assertFalse(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(328, 400));
        assertTrue(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.MARKER, gesture.update(380, 320));
        assertEquals(RecordingGesture.Direction.MARKER, gesture.update(320, 380));
        assertFalse(gesture.isCentered());
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(328, 400));
        assertTrue(gesture.isCentered());
        assertTrue(gesture.isArmed());
        assertFalse(gesture.onUp());
    }

    @Test public void explicitHomeMustBeFinitePositiveAndSmallerThanActivationDistance() {
        for (float invalidHome : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, 112, 113}) {
            assertThrows(IllegalArgumentException.class, () -> new RecordingGesture(112, invalidHome));
        }
        for (float invalidDistance : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new RecordingGesture(invalidDistance, 28));
        }
    }

    @Test public void ordinaryReleaseSavesExactlyOnce() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDown(300);
        assertFalse(gesture.onMove(250));
        assertTrue(gesture.onUp());
        assertFalse(gesture.isCentered());
        assertFalse(gesture.isArmed());
        assertFalse(gesture.onUp());
    }

    @Test public void offCenterJitterAndExtremeMovesCannotActUntilFixedHomeIsObserved() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDownAt(300, 400, 360, 340);
        assertEquals(RecordingGesture.Direction.NONE, gesture.getDirection());
        assertFalse(gesture.isCentered());
        assertFalse(gesture.isArmed());
        for (float[] point : new float[][]{{360, 340}, {361, 339}, {500, 100},
                {-500, 400}, {300, 800}, {300, 0}, {800, 400}}) {
            assertEquals(RecordingGesture.Direction.NONE, gesture.update(point[0], point[1]));
            assertFalse(gesture.isCentered());
            assertFalse(gesture.isArmed());
        }
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(300, 400));
        assertTrue(gesture.isCentered());
        assertTrue(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.MARKER, gesture.update(360, 340));
        assertFalse(gesture.isArmed());
        assertFalse(gesture.onUp());
    }

    @Test public void visitingFixedHomeAfterOffCenterPressArmsEachCardinalAction() {
        RecordingGesture gesture = new RecordingGesture(80);
        float[][] points = {{420, 600}, {580, 600}, {500, 520}, {500, 680}};
        RecordingGesture.Direction[] expected = {RecordingGesture.Direction.LEFT, RecordingGesture.Direction.RIGHT,
                RecordingGesture.Direction.UP, RecordingGesture.Direction.DOWN};
        for (int i = 0; i < points.length; i++) {
            gesture.onDownAt(500, 600, 560, 540);
            assertEquals(RecordingGesture.Direction.NONE, gesture.update(points[i][0], points[i][1]));
            assertFalse(gesture.isArmed());
            assertEquals(RecordingGesture.Direction.NONE, gesture.update(500, 600));
            assertTrue(gesture.isCentered());
            assertTrue(gesture.isArmed());
            assertEquals(expected[i], gesture.update(points[i][0], points[i][1]));
            assertFalse(gesture.onUp());
        }
    }

    @Test public void fixedHomeAtPressUsesAnInclusiveRadialBoundary() {
        RecordingGesture gesture = new RecordingGesture(100);
        gesture.onDownAt(300, 400, 335, 400);
        assertTrue(gesture.isCentered());
        assertTrue(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.RIGHT, gesture.update(400, 400));

        // Component-wise bounds would accept this point, but its radius exceeds 35.
        gesture.onDownAt(300, 400, 325, 425);
        assertFalse(gesture.isCentered());
        assertFalse(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(325, 425));
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(335, 400));
        assertTrue(gesture.isCentered());
        assertTrue(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.RIGHT, gesture.update(400, 400));
    }

    @Test public void offCenterPressWithoutAnyActionStillHasAnOrdinaryRelease() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDown(0, 0);
        assertEquals(RecordingGesture.Direction.UP, gesture.update(0, -80));
        gesture.onDownAt(300, 400, 340, 400);
        assertEquals(RecordingGesture.Direction.NONE, gesture.getDirection());
        assertFalse(gesture.isLocked());
        assertFalse(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(1000, 1000));
        assertTrue(gesture.onUp());
        assertFalse(gesture.onUp());
        assertFalse(gesture.isCentered());
        assertFalse(gesture.isArmed());
    }

    @Test public void upwardSlideLocksAndReleaseKeepsRecording() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDown(300);
        assertTrue(gesture.onMove(220));
        assertFalse(gesture.onMove(180));
        assertTrue(gesture.isLocked());
        assertFalse(gesture.onUp());
        assertTrue(gesture.isLocked());
    }

    @Test public void downwardMotionDoesNotLockAndResetAllowsNextCapture() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDown(300);
        assertFalse(gesture.onMove(500));
        assertTrue(gesture.onUp());
        gesture.onDown(300);
        assertTrue(gesture.onMove(210));
        gesture.reset();
        assertFalse(gesture.isLocked());
        assertFalse(gesture.onUp());
        gesture.onDown(100);
        assertTrue(gesture.onUp());
    }

    @Test public void motionWithoutPressCannotLock() {
        RecordingGesture gesture = new RecordingGesture(80);
        assertFalse(gesture.onMove(-1000));
        assertFalse(gesture.isLocked());
        assertFalse(gesture.onUp());
    }

    @Test public void fiveDirectionsUseThePressOrigin() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDown(300, 400);
        assertEquals(RecordingGesture.Direction.LEFT, gesture.update(220, 400));
        assertFalse(gesture.isLocked());
        gesture.onDown(300, 400);
        assertEquals(RecordingGesture.Direction.RIGHT, gesture.update(380, 400));
        assertFalse(gesture.isLocked());
        gesture.onDown(300, 400);
        assertEquals(RecordingGesture.Direction.DOWN, gesture.update(300, 480));
        assertFalse(gesture.isLocked());
        gesture.onDown(300, 400);
        assertEquals(RecordingGesture.Direction.UP, gesture.update(300, 320));
        assertTrue(gesture.isLocked());
        gesture.onDown(300, 400);
        assertEquals(RecordingGesture.Direction.MARKER, gesture.update(360, 340));
        assertFalse(gesture.isLocked());
    }

    @Test public void smallMovementRemainsAnOrdinaryHold() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDown(300, 400);
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(221, 400));
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(300, 321));
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(379, 479));
        assertTrue(gesture.onUp());
        assertFalse(gesture.onUp());
    }

    @Test public void diagonalMovementWaitsForAClearDominantAxis() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDown(300, 400);
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(200, 300));
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(175, 299));
        assertEquals(RecordingGesture.Direction.LEFT, gesture.update(175, 300));
    }

    @Test public void leftLingersWithoutRetriggeringUntilCenterIsObserved() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDown(300, 400);
        assertEquals(RecordingGesture.Direction.LEFT, gesture.update(210, 400));
        assertFalse(gesture.isArmed());
        assertFalse(gesture.isCentered());
        assertEquals(RecordingGesture.Direction.LEFT, gesture.update(211, 399));
        assertEquals(RecordingGesture.Direction.LEFT, gesture.update(209, 401));
        assertEquals(RecordingGesture.Direction.LEFT, gesture.update(500, 400));
        assertEquals(RecordingGesture.Direction.LEFT, gesture.update(300, 100));
        assertFalse(gesture.isArmed());
        assertFalse(gesture.isLocked());
        assertFalse(gesture.onUp());
        assertEquals(RecordingGesture.Direction.LEFT, gesture.getDirection());
    }

    @Test public void leftCenterLeftAllowsTwoPhotoActionsInOnePress() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDown(300, 400);
        assertTrue(gesture.isArmed());
        assertTrue(gesture.isCentered());
        assertEquals(RecordingGesture.Direction.LEFT, gesture.update(220, 400));
        assertFalse(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(300, 400));
        assertTrue(gesture.isArmed());
        assertTrue(gesture.isCentered());
        assertEquals(RecordingGesture.Direction.LEFT, gesture.update(220, 400));
        assertFalse(gesture.isArmed());
        assertFalse(gesture.onUp());
    }

    @Test public void rearmingRequiresTheRadialCenterAndIncludesItsBoundary() {
        RecordingGesture gesture = new RecordingGesture(100);
        gesture.onDown(0, 0);
        assertEquals(RecordingGesture.Direction.LEFT, gesture.update(-100, 0));
        // Both components are below 35, but their combined distance exceeds 35.
        assertEquals(RecordingGesture.Direction.LEFT, gesture.update(25, 25));
        assertFalse(gesture.isCentered());
        assertFalse(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(35, 0));
        assertTrue(gesture.isCentered());
        assertTrue(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(35.01f, 0));
        assertFalse(gesture.isCentered());
        assertTrue(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.LEFT, gesture.update(-100, 0));
    }

    @Test public void videoExitsItsAxisZoneAndCannotRestartUntilCenter() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDown(0, 0);
        assertEquals(RecordingGesture.Direction.RIGHT, gesture.update(80, 0));
        assertEquals(RecordingGesture.Direction.RIGHT, gesture.update(80, 64));
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(80, 65));
        assertFalse(gesture.isArmed());
        assertFalse(gesture.isCentered());
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(80, 0));
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(-100, 0));
        assertFalse(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(0, 0));
        assertTrue(gesture.isArmed());
        assertTrue(gesture.isCentered());
        assertEquals(RecordingGesture.Direction.RIGHT, gesture.update(80, 0));
        assertFalse(gesture.onUp());
    }

    @Test public void videoLeavingDistanceZoneAlsoDisarmsReentry() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDown(0, 0);
        assertEquals(RecordingGesture.Direction.RIGHT, gesture.update(90, 0));
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(79, 0));
        assertFalse(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(90, 0));
        assertFalse(gesture.onUp());
    }

    @Test public void videoReturningDirectlyToCenterRearmsOnThatUpdate() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDown(0, 0);
        assertEquals(RecordingGesture.Direction.RIGHT, gesture.update(80, 0));
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(28, 0));
        assertTrue(gesture.isCentered());
        assertTrue(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.LEFT, gesture.update(-80, 0));
        assertFalse(gesture.isArmed());
    }

    @Test public void rightZoneRequiresBothDistanceAndExactAxisDominance() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDown(0, 0);
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(79.99f, 0));
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(80, 65));
        assertTrue(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.RIGHT, gesture.update(80, 64));
        assertFalse(gesture.isArmed());
    }

    @Test public void markerUsesRadialDistanceRatherThanEitherAxisThreshold() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDown(0, 0);
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(47.4f, -63.2f));
        assertTrue(gesture.isArmed());
        // A 48-by-64 diagonal is exactly 80, while both components are below 80.
        assertEquals(RecordingGesture.Direction.MARKER, gesture.update(48, -64));
        assertFalse(gesture.isArmed());
        assertFalse(gesture.isLocked());
    }

    @Test public void inclusiveMarkerWedgeTakesPriorityOverUpAndRight() {
        assertEquals(RecordingGesture.Direction.MARKER, RecordingGesture.candidateDirection(80, -40));
        assertEquals(RecordingGesture.Direction.MARKER, RecordingGesture.candidateDirection(40, -80));
        assertEquals(RecordingGesture.Direction.RIGHT, RecordingGesture.candidateDirection(80, -39.99f));
        assertEquals(RecordingGesture.Direction.UP, RecordingGesture.candidateDirection(39.99f, -80));
        assertEquals(RecordingGesture.Direction.MARKER, RecordingGesture.candidateDirection(2, -1));
        assertEquals(RecordingGesture.Direction.MARKER, RecordingGesture.candidateDirection(1, -2));
        RecordingGesture gesture = new RecordingGesture(80);
        for (float[] point : new float[][]{{80, -40}, {40, -80}}) {
            gesture.onDown(0, 0);
            assertEquals(RecordingGesture.Direction.MARKER, gesture.update(point[0], point[1]));
        }
    }

    @Test public void markerLingersUntilRadialHomeThenAllowsAnotherMarker() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDown(300, 400);
        assertEquals(RecordingGesture.Direction.MARKER, gesture.update(360, 340));
        assertEquals(RecordingGesture.Direction.MARKER, gesture.update(361, 339));
        assertEquals(RecordingGesture.Direction.MARKER, gesture.update(300, 300));
        assertEquals(RecordingGesture.Direction.MARKER, gesture.update(400, 400));
        assertFalse(gesture.isArmed());
        // Each component is within 28, but the radial distance is still outside home.
        assertEquals(RecordingGesture.Direction.MARKER, gesture.update(320, 380));
        assertFalse(gesture.isCentered());
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(328, 400));
        assertTrue(gesture.isCentered());
        assertTrue(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.MARKER, gesture.update(360, 340));
        assertFalse(gesture.isArmed());
        assertFalse(gesture.onUp());
    }

    @Test public void enteringMarkerZoneEndsVideoWithoutCreatingAMarkerUntilHome() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDown(0, 0);
        assertEquals(RecordingGesture.Direction.RIGHT, gesture.update(100, 0));
        assertEquals(RecordingGesture.Direction.RIGHT, gesture.update(100, -49.99f));
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(100, -50));
        assertFalse(gesture.isArmed());
        assertFalse(gesture.isCentered());
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(100, -100));
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(100, 0));
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(0, 0));
        assertTrue(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.MARKER, gesture.update(60, -60));
    }

    @Test public void markerReturnedHomeNeverBecomesAnOrdinaryRelease() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDown(0, 0);
        assertEquals(RecordingGesture.Direction.MARKER, gesture.update(60, -60));
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(0, 0));
        assertTrue(gesture.isArmed());
        assertFalse(gesture.onUp());
        assertFalse(gesture.onUp());
        assertFalse(gesture.isCentered());
        assertFalse(gesture.isArmed());
        gesture.onDown(0, 0);
        assertTrue(gesture.onUp());
    }

    @Test public void resetCancelsMarkerAndAReleasedMarkerCannotRetrigger() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDown(0, 0);
        assertEquals(RecordingGesture.Direction.MARKER, gesture.update(60, -60));
        assertFalse(gesture.onUp());
        assertEquals(RecordingGesture.Direction.MARKER, gesture.update(0, 0));
        assertFalse(gesture.isArmed());
        assertFalse(gesture.isLocked());
        gesture.reset();
        assertEquals(RecordingGesture.Direction.NONE, gesture.getDirection());
        assertFalse(gesture.isArmed());
        assertFalse(gesture.isCentered());
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(60, -60));
        assertFalse(gesture.onUp());
        gesture.onDown(0, 0);
        assertEquals(RecordingGesture.Direction.MARKER, gesture.update(60, -60));
    }

    @Test public void upAndDownStayLatchedWhileCenteredStateFollowsTheFinger() {
        RecordingGesture gesture = new RecordingGesture(80);
        for (float dy : new float[]{-80, 80}) {
            RecordingGesture.Direction expected = dy < 0 ? RecordingGesture.Direction.UP : RecordingGesture.Direction.DOWN;
            gesture.onDown(0, 0);
            assertEquals(expected, gesture.update(0, dy));
            assertFalse(gesture.isCentered());
            assertFalse(gesture.isArmed());
            assertEquals(expected, gesture.update(0, 0));
            assertTrue(gesture.isCentered());
            assertFalse(gesture.isArmed());
            assertEquals(expected, gesture.update(100, 0));
            assertFalse(gesture.isCentered());
            assertFalse(gesture.isArmed());
            assertFalse(gesture.onUp());
            assertEquals(expected, gesture.getDirection());
            assertEquals(expected == RecordingGesture.Direction.UP, gesture.isLocked());
        }
    }

    @Test public void releaseAfterAnActionThenCenterNeverBecomesAnOrdinaryStopTap() {
        RecordingGesture gesture = new RecordingGesture(80);
        for (float dx : new float[]{-80, 80}) {
            gesture.onDown(0, 0);
            assertNotEquals(RecordingGesture.Direction.NONE, gesture.update(dx, 0));
            assertEquals(RecordingGesture.Direction.NONE, gesture.update(0, 0));
            assertTrue(gesture.isArmed());
            assertFalse(gesture.onUp());
            assertFalse(gesture.onUp());
            gesture.onDown(0, 0);
            assertTrue(gesture.isArmed());
            assertTrue(gesture.isCentered());
            assertTrue(gesture.onUp());
        }
    }

    @Test public void upLockSurvivesReleaseUntilTheNextPress() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDown(300, 400);
        assertEquals(RecordingGesture.Direction.UP, gesture.update(300, 310));
        assertFalse(gesture.onUp());
        assertTrue(gesture.isLocked());
        assertEquals(RecordingGesture.Direction.UP, gesture.update(600, 700));
        gesture.onDown(600, 700);
        assertEquals(RecordingGesture.Direction.NONE, gesture.getDirection());
        assertFalse(gesture.isLocked());
        assertTrue(gesture.onUp());
    }

    @Test public void directionalReleaseNeverLooksLikeAStopTap() {
        RecordingGesture gesture = new RecordingGesture(80);
        for (float[] point : new float[][]{{-80, 0}, {80, 0}, {0, 80}, {0, -80}, {60, -60}}) {
            gesture.onDown(0, 0);
            assertNotEquals(RecordingGesture.Direction.NONE, gesture.update(point[0], point[1]));
            assertFalse(gesture.onUp());
            assertFalse(gesture.onUp());
        }
    }

    @Test public void resetCancelsDirectionAndIgnoresMotionUntilAnotherPress() {
        RecordingGesture gesture = new RecordingGesture(80);
        gesture.onDown(0, 0);
        assertEquals(RecordingGesture.Direction.DOWN, gesture.update(0, 100));
        gesture.reset();
        assertEquals(RecordingGesture.Direction.NONE, gesture.getDirection());
        assertFalse(gesture.isCentered());
        assertFalse(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.NONE, gesture.update(1000, 0));
        assertFalse(gesture.onUp());
        gesture.onDown(500, 500);
        assertTrue(gesture.isCentered());
        assertTrue(gesture.isArmed());
        assertEquals(RecordingGesture.Direction.RIGHT, gesture.update(580, 500));
    }

    @Test public void thresholdMustBeFiniteAndPositive() {
        for (float invalid : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new RecordingGesture(invalid));
        }
    }
}
