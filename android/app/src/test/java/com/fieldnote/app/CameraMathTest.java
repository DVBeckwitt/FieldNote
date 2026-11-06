package com.fieldnote.app;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class CameraMathTest {
    @Test public void rearCameraJpegOrientationFollowsDisplayRotation() {
        assertEquals(90, CameraMath.jpegRotation(90, 0, false));
        assertEquals(0, CameraMath.jpegRotation(90, 90, false));
        assertEquals(270, CameraMath.jpegRotation(90, 180, false));
        assertEquals(180, CameraMath.jpegRotation(90, 270, false));
    }

    @Test public void frontCameraAccountsForOppositeFacingDirection() {
        assertEquals(270, CameraMath.jpegRotation(270, 0, true));
        assertEquals(0, CameraMath.jpegRotation(270, 90, true));
    }

    @Test public void captureRotationTracksPhoneEvenWhenDisplayIsPortraitLocked() {
        assertEquals(90, CameraMath.deviceCaptureRotation(90, 0, false));
        assertEquals(0, CameraMath.deviceCaptureRotation(90, 270, false));
        assertEquals(180, CameraMath.deviceCaptureRotation(90, 90, false));
        assertEquals(0, CameraMath.deviceCaptureRotation(270, 270, true));
    }

    @Test public void realtimeSensorClockMapsExposureToWallTime() {
        assertEquals(999_800L, CameraMath.sensorWallTime(9_800_000_000L, true, 1_000_000L, 10_000_000_000L));
        assertEquals(1_000_000L, CameraMath.sensorWallTime(9_800_000_000L, false, 1_000_000L, 10_000_000_000L));
        assertEquals(1_000_000L, CameraMath.sensorWallTime(11_000_000_000L, true, 1_000_000L, 10_000_000_000L));
    }

    @Test public void videoEndUsesMonotonicIntervalRatherThanChangedSystemClock() {
        assertEquals(105_000L, CameraMath.endTime(100_000L, 20_000L, 25_000L));
        assertEquals(100_000L, CameraMath.endTime(100_000L, 20_000L, 19_000L));
    }
}
