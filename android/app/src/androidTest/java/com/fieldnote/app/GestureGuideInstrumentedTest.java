package com.fieldnote.app;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Native Canvas coverage for state retained between gesture-guide draw frames. */
@RunWith(AndroidJUnit4.class)
public final class GestureGuideInstrumentedTest {
    @Test public void centeredGuideKeepsTargetGradientsAcrossConsecutiveFrames() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Bitmap[] frames = new Bitmap[2];
        int[] sample = new int[3];
        instrumentation.runOnMainSync(() -> {
            Context context = instrumentation.getTargetContext();
            float density = context.getResources().getDisplayMetrics().density;
            int size = Math.round(320 * density);
            float center = size / 2f;
            GestureGuideView guide = new GestureGuideView(context,
                    RecordingGesture.ACTIVATION_DISTANCE_DP * density,
                    RecordingGesture.HOME_RADIUS_DP * density);
            guide.layout(0, 0, size, size);
            // An unattached guide renders immediately without time-dependent visibility fades.
            guide.begin(center, center);
            sample[0] = Math.round(center - 80 * density);
            sample[1] = Math.round(center + 80 * density);
            sample[2] = Math.round(center);
            frames[0] = drawFrame(guide, size);
            frames[1] = drawFrame(guide, size);
            guide.finish();
        });
        try {
            int firstLeft = frames[0].getPixel(sample[0], sample[2]);
            int firstRight = frames[0].getPixel(sample[1], sample[2]);
            assertTrue("The first photo trail is actually rendered", Color.alpha(firstLeft) > 0);
            assertTrue("The first video trail is actually rendered", Color.alpha(firstRight) > 0);
            assertEquals("The photo trail must not inherit the transparent home dot's alpha",
                    firstLeft, frames[1].getPixel(sample[0], sample[2]));
            assertEquals("The opposite trail also stays stable",
                    firstRight, frames[1].getPixel(sample[1], sample[2]));
            assertTrue("Drawing the same centered guide twice must preserve every target",
                    frames[0].sameAs(frames[1]));
        } finally {
            for (Bitmap frame : frames) if (frame != null) frame.recycle();
        }
    }

    private static Bitmap drawFrame(GestureGuideView guide, int size) {
        Bitmap frame = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        guide.draw(new Canvas(frame));
        return frame;
    }
}
