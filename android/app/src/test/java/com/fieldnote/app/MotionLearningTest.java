package com.fieldnote.app;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Base64;
import java.nio.ByteBuffer;

import static org.junit.Assert.*;

/** Synthetic motion evidence only: this does not establish comfortable thresholds on a real phone. */
public class MotionLearningTest {
    @Test public void defaultsRemainForwardAudioAndEitherTwistWithMarkerUnassigned() {
        MotionSettings settings = new MotionSettings();
        for (MotionAction action : MotionAction.values()) assertEquals(50, settings.getSensitivity(action));
        assertEquals(MotionAction.AUDIO, settings.recognize(forward(1)));
        assertEquals(MotionAction.PHOTO, settings.recognize(twist(1)));
        assertEquals(MotionAction.PHOTO, settings.recognize(twist(-1)));
        assertNull(settings.recognize(lateral(0, 1, 1, 0)));
        assertFalse(settings.isTrained(MotionAction.MARKER));
    }

    @Test public void taughtPhotoForwardOverridesDefaultAudioExactlyOnce() {
        MotionSettings settings = teach(new MotionSettings(), MotionAction.PHOTO, forward(1));
        assertFalse(settings.isTrained(MotionAction.AUDIO));
        assertEquals(MotionAction.PHOTO, settings.recognize(forward(.85)));
        assertEquals(MotionAction.PHOTO, settings.recognize(forward(1.2)));
    }

    @Test public void taughtAudioTwistOverridesDefaultPhoto() {
        MotionSettings settings = teach(new MotionSettings(), MotionAction.AUDIO, twist(1));
        assertFalse(settings.isTrained(MotionAction.PHOTO));
        assertEquals(MotionAction.AUDIO, settings.recognize(twist(.9)));
        assertEquals(MotionAction.PHOTO, settings.recognize(twist(-1)));
    }

    @Test public void weakNearShapeCannotFallBackToWrongDefaultAction() {
        MotionSettings settings = teach(new MotionSettings(), MotionAction.PHOTO, forward(2.5));
        assertEquals("The weak trace independently qualifies for the default audio gesture",
                MotionAction.AUDIO, new MotionSettings().recognize(forward(1)));
        assertNull("Below the taught strength envelope, ignore its shape instead of toggling audio",
                settings.recognize(forward(1)));
        assertEquals(MotionAction.PHOTO, settings.withSensitivity(MotionAction.PHOTO, 100).recognize(forward(1)));
    }

    @Test public void markerLearningKeepsUnrelatedDefaultsAndToleratesHeldOutVariation() {
        MotionSettings settings = teach(new MotionSettings(), MotionAction.MARKER, lateral(0, 1, 1, 0));
        assertEquals(MotionAction.AUDIO, settings.recognize(forward(1)));
        assertEquals(MotionAction.PHOTO, settings.recognize(twist(1)));
        assertEquals(MotionAction.PHOTO, settings.recognize(twist(-1)));
        assertEquals(MotionAction.MARKER, settings.recognize(lateral(18, .8, 1.35, .08)));
        assertNull(settings.recognize(lateral(180, 1, 1, 0)));
        assertNull(settings.recognize(lateral(0, .12, 1, 0)));
    }

    @Test public void everyDemonstrationMustAgreeBeforeReplacingAProfile() {
        MotionSettings original = teach(new MotionSettings(), MotionAction.MARKER, lateral(90, 1, 1, 0));
        String before = original.encode();
        MotionCalibration session = new MotionCalibration(MotionAction.MARKER, original);
        assertEquals(1, session.addDemonstration(lateral(0, 1, 1, 0)).completed);
        assertEquals(2, session.addDemonstration(lateral(8, .95, 1.1, 0)).completed);
        MotionCalibration.Result outlier = session.addDemonstration(lateral(180, 1, 1, 0));
        assertFalse(outlier.accepted);
        assertEquals(2, outlier.completed);
        assertNull(outlier.settings);
        assertEquals(before, original.encode());
        MotionCalibration.Result complete = session.addDemonstration(lateral(-8, 1.1, .95, 0));
        assertTrue(complete.accepted);
        assertNotNull(complete.settings);
        assertEquals(MotionAction.MARKER, complete.settings.recognize(lateral(0, 1, 1, 0)));
        assertEquals(before, original.encode());
    }

    @Test public void cancellationOrRepeatedRejectionPreservesExistingSettings() {
        MotionSettings original = teach(new MotionSettings(), MotionAction.MARKER, lateral(0, 1, 1, 0));
        String before = original.encode();
        MotionCalibration cancelled = new MotionCalibration(MotionAction.MARKER, original);
        cancelled.addDemonstration(lateral(90, 1, 1, 0));
        cancelled.cancel();
        assertNull(cancelled.addDemonstration(lateral(90, 1, 1, 0)).settings);
        MotionCalibration rejected = new MotionCalibration(MotionAction.PHOTO, original);
        MotionCalibration.Result last = null;
        for (int i = 0; i < 12; i++) last = rejected.addDemonstration(lateral(0, .02, 1, 0));
        assertNotNull(last);
        assertTrue(last.terminal);
        assertFalse(last.accepted);
        assertNull(last.settings);
        assertEquals(before, original.encode());
    }

    @Test public void completedModelMustRecognizeAllThreeAcceptedDemonstrationsAtSelectedSensitivity() {
        MotionSettings original = new MotionSettings();
        MotionCalibration session = new MotionCalibration(MotionAction.MARKER, original);
        assertTrue(session.addDemonstration(lateral(0, 1, 1, 0)).accepted);
        assertTrue(session.addDemonstration(lateral(0, 2, 1, 0)).accepted);
        MotionCalibration.Result excessive = session.addDemonstration(lateral(0, 2.3, 1, 0));
        assertFalse("A saved model must not reject its first accepted example", excessive.accepted);
        assertTrue("Reject through final-model replay, not a pairwise example bound", excessive.message.contains("sensitivity"));
        assertNull(excessive.settings);
        assertEquals(2, excessive.completed);
        assertFalse(original.isTrained(MotionAction.MARKER));
    }

    @Test public void anotherLearnedActionCannotReuseTheSameMotionEvenAtDifferentStrength() {
        MotionSettings settings = teach(new MotionSettings(), MotionAction.AUDIO, lateral(0, 1, 1, 0));
        MotionCalibration training = new MotionCalibration(MotionAction.MARKER, settings);
        MotionCalibration.Result conflict = training.addDemonstration(lateral(5, 2.8, 1, 0));
        assertFalse(conflict.accepted);
        assertEquals(0, conflict.completed);
        assertNull(conflict.settings);
    }

    @Test public void overlappingLearnedMatchesAreRejectedWithoutEnumPriority() {
        MotionSettings settings = teach(new MotionSettings(), MotionAction.AUDIO, lateral(0, 1, 1, 0));
        settings = teach(settings, MotionAction.MARKER, lateral(70, 1, 1, 0));
        settings = settings.withSensitivity(MotionAction.AUDIO, 100).withSensitivity(MotionAction.MARKER, 100);
        assertNull(settings.recognize(lateral(35, 1, 1, 0)));
        assertEquals(MotionAction.AUDIO, settings.recognize(lateral(0, 1, 1, 0)));
        assertEquals(MotionAction.MARKER, settings.recognize(lateral(70, 1, 1, 0)));
    }

    @Test public void higherSensitivityWidensLearnedAngleAndMinimumStrengthIndependently() {
        MotionSettings learned = teach(new MotionSettings(), MotionAction.MARKER, lateral(0, 1, 1, 0));
        MotionSettings low = learned.withSensitivity(MotionAction.MARKER, 0);
        MotionSettings high = learned.withSensitivity(MotionAction.MARKER, 100);
        assertNull(low.recognize(lateral(30, 1, 1, 0)));
        assertEquals(MotionAction.MARKER, high.recognize(lateral(30, 1, 1, 0)));
        assertNull(low.recognize(lateral(0, .5, 1, 0)));
        assertEquals(MotionAction.MARKER, high.recognize(lateral(0, .5, 1, 0)));
        assertEquals(50, high.getSensitivity(MotionAction.AUDIO));
        assertEquals(50, high.getSensitivity(MotionAction.PHOTO));
    }

    @Test public void sensitivityAlsoChangesDefaultGesturesWithoutTeaching() {
        MotionSettings defaults = new MotionSettings();
        assertNull(defaults.withSensitivity(MotionAction.AUDIO, 0).recognize(forward(.75)));
        assertEquals(MotionAction.AUDIO, defaults.withSensitivity(MotionAction.AUDIO, 100).recognize(forward(.75)));
        assertNull(defaults.withSensitivity(MotionAction.PHOTO, 0).recognize(twist(.7)));
        assertEquals(MotionAction.PHOTO, defaults.withSensitivity(MotionAction.PHOTO, 100).recognize(twist(.7)));
    }

    @Test public void serializationRoundTripsThreeIndependentProfilesAndResetKeepsSensitivity() {
        MotionSettings settings = teach(new MotionSettings(), MotionAction.AUDIO, lateral(90, 1, 1, 0));
        settings = teach(settings, MotionAction.PHOTO, lateral(-90, 1, 1, 0));
        settings = teach(settings, MotionAction.MARKER, lateral(0, 1, 1, 0));
        settings = settings.withSensitivity(MotionAction.AUDIO, 12).withSensitivity(MotionAction.PHOTO, 83)
                .withSensitivity(MotionAction.MARKER, 67);
        MotionSettings restored = MotionSettings.decode(settings.encode());
        assertEquals(settings.encode(), restored.encode());
        assertEquals(12, restored.getSensitivity(MotionAction.AUDIO));
        assertEquals(83, restored.getSensitivity(MotionAction.PHOTO));
        assertEquals(67, restored.getSensitivity(MotionAction.MARKER));
        assertEquals(MotionAction.MARKER, restored.recognize(lateral(0, 1, 1, 0)));
        MotionSettings reset = restored.resetTraining(MotionAction.MARKER);
        assertFalse(reset.isTrained(MotionAction.MARKER));
        assertEquals(67, reset.getSensitivity(MotionAction.MARKER));
        assertTrue(reset.isTrained(MotionAction.AUDIO));
        assertTrue(reset.isTrained(MotionAction.PHOTO));
        assertNull(reset.recognize(lateral(0, 1, 1, 0)));
    }

    @Test public void invalidImportsCannotAllocateUnboundedModelsOrAcceptBadSamples() {
        assertThrows(IllegalArgumentException.class, () -> MotionSettings.decode("not a model"));
        assertThrows(IllegalArgumentException.class, () -> MotionSettings.decode("A".repeat(12001)));
        assertThrows(IllegalArgumentException.class, () -> new MotionSettings().withSensitivity(MotionAction.AUDIO, 101));
        MotionSequence.Builder invalid = new MotionSequence.Builder();
        for (int i = 0; i < 8; i++) invalid.add(i * 20_000_000L, Float.NaN, 0, 0, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, invalid::build);
    }

    @Test public void absentPreferenceUsesDefaultsButMalformedExistingValuesRequireRecovery() {
        assertEquals(MotionAction.AUDIO, MotionSettings.decodePreference(null).recognize(forward(1)));
        assertThrows(IllegalArgumentException.class, () -> MotionSettings.decodePreference(7));
        assertThrows(IllegalArgumentException.class, () -> MotionSettings.decodePreference(true));
        assertThrows(IllegalArgumentException.class, () -> MotionSettings.decodePreference(""));
        assertThrows(IllegalArgumentException.class, () -> MotionSettings.decodePreference("broken model"));
        MotionSettings photo = teach(new MotionSettings(), MotionAction.PHOTO, forward(1));
        assertEquals(MotionAction.PHOTO, MotionSettings.decodePreference(photo.encode()).recognize(forward(1)));
    }

    @Test public void finiteButImpossibleStoredShapesAndStrengthMetadataAreRejected() {
        MotionSettings original = teach(new MotionSettings(), MotionAction.AUDIO, lateral(0, 1, 1, 0));
        for (float replacement : new float[]{0, 11}) {
            byte[] bytes = Base64.getDecoder().decode(original.encode());
            ByteBuffer data = ByteBuffer.wrap(bytes);
            // Version1 header, first action sensitivity/count, then duration/peak/RMS.
            for (int i = 0; i < MotionSequence.POINTS * MotionSequence.AXES; i++) data.putFloat(24 + i * 4, replacement);
            String malformed = Base64.getEncoder().encodeToString(bytes);
            assertThrows(IllegalArgumentException.class, () -> MotionSettings.decode(malformed));
        }
        byte[] bytes = Base64.getDecoder().decode(original.encode());
        ByteBuffer data = ByteBuffer.wrap(bytes);
        data.putFloat(16, data.getFloat(16) * 2);
        assertThrows(IllegalArgumentException.class,
                () -> MotionSettings.decode(Base64.getEncoder().encodeToString(bytes)));
        assertEquals(original.encode(), MotionSettings.decode(original.encode()).encode());
    }

    @Test public void gradualStreamOnsetSurvivesDeadbandAndProducesOneWholeMotion() {
        MotionSegmenter segmenter = new MotionSegmenter();
        List<MotionSequence> found = new ArrayList<>();
        long time = 1_000_000_000L;
        for (int i = 0; i < 30; i++) add(found, segmenter.add(time += 20_000_000L, 0, 0, 0, 0, 0, 0));
        for (float value : new float[]{1.3f, 1.7f, 2.5f, 5, 9, 10, 10, 10, 7, 3, 0, 0, -8, -8, -8, -8, -6, -3, 0})
            add(found, segmenter.add(time += 20_000_000L, value, 0, 0, 0, 0, 0));
        for (int i = 0; i < 20; i++) add(found, segmenter.add(time += 20_000_000L, 0, 0, 0, 0, 0, 0));
        assertEquals(1, found.size());
        MotionSettings trained = teach(new MotionSettings(), MotionAction.MARKER, found.get(0));
        assertEquals(MotionAction.MARKER, trained.recognize(found.get(0)));
    }

    @Test public void streamRequiresQuietAndCooldownAndResetDropsHalfAGesture() {
        MotionSegmenter segmenter = new MotionSegmenter();
        List<MotionSequence> found = new ArrayList<>();
        long time = 1_000_000_000L;
        for (int i = 0; i < 30; i++) add(found, segmenter.add(time += 20_000_000L, 0, 0, 0, 0, 0, 0));
        time = stream(segmenter, found, lateral(0, 1, 1, 0), time);
        for (int i = 0; i < 10; i++) add(found, segmenter.add(time += 20_000_000L, 0, 0, 0, 0, 0, 0));
        assertEquals(1, found.size());
        time = stream(segmenter, found, lateral(0, 1, 1, 0), time);
        for (int i = 0; i < 80; i++) add(found, segmenter.add(time += 20_000_000L, 0, 0, 0, 0, 0, 0));
        assertEquals("Return stroke within the shared refractory period is not another event", 1, found.size());
        for (int i = 0; i < 5; i++) add(found, segmenter.add(time += 20_000_000L, 10, 0, 0, 0, 0, 0));
        segmenter.reset();
        for (int i = 0; i < 6; i++) add(found, segmenter.add(time += 20_000_000L, -8, 0, 0, 0, 0, 0));
        for (int i = 0; i < 20; i++) add(found, segmenter.add(time += 20_000_000L, 0, 0, 0, 0, 0, 0));
        assertEquals(1, found.size());
    }

    private static void add(List<MotionSequence> found, MotionSequence sequence) { if (sequence != null) found.add(sequence); }
    private static long stream(MotionSegmenter segmenter, List<MotionSequence> found, MotionSequence sequence, long time) {
        for (float[] sample : sequence.samples)
            add(found, segmenter.add(time += 20_000_000L, sample[0], sample[1], sample[2], sample[3], sample[4], sample[5]));
        return time;
    }
    private static MotionSettings teach(MotionSettings settings, MotionAction action, MotionSequence sequence) {
        MotionCalibration session = new MotionCalibration(action, settings);
        MotionCalibration.Result result = null;
        for (int i = 0; i < 3; i++) {
            result = session.addDemonstration(sequence);
            assertTrue(result.message, result.accepted);
        }
        assertNotNull(result.settings);
        return result.settings;
    }
    private static MotionSequence forward(double strength) {
        MotionSequence.Builder builder = new MotionSequence.Builder();
        for (int t = 0; t <= 380; t += 20) {
            float z = (float) (-pulse(t) * strength);
            builder.add(t * 1_000_000L, 0, 0, z, 0, 0, 0);
        }
        return builder.build();
    }
    private static MotionSequence twist(double strength) {
        MotionSequence.Builder builder = new MotionSequence.Builder();
        for (int t = 0; t <= 300; t += 20)
            builder.add(t * 1_000_000L, 0, 0, 0, 0, 0, t < 200 ? (float) (4 * strength) : 0);
        return builder.build();
    }
    private static MotionSequence lateral(double angle, double strength, double speed, double noise) {
        MotionSequence.Builder builder = new MotionSequence.Builder();
        double radians = Math.toRadians(angle);
        for (int t = 0; t <= 380; t += 20) {
            double value = pulse(t) * strength;
            builder.add(Math.round(t * speed * 1_000_000), (float) (value * Math.cos(radians) + noise * Math.sin(t)),
                    (float) (value * Math.sin(radians) + noise * Math.cos(t)), 0, 0, 0, 0);
        }
        return builder.build();
    }
    private static double pulse(int t) { return t < 120 ? 10 : t < 160 ? 0 : t < 280 ? -8 : 0; }
}
