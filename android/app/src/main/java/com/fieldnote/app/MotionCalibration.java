package com.fieldnote.app;

import java.util.ArrayList;
import java.util.List;

/** Three consistent demonstrations replace one profile only after complete validation. */
public final class MotionCalibration {
    public static final int REQUIRED = 3;
    private final MotionAction action;
    private final MotionSettings original;
    private final List<MotionSequence.Shape> examples = new ArrayList<>();
    private final List<MotionSequence> demonstrations = new ArrayList<>();
    private int attempts;
    private boolean closed;

    public MotionCalibration(MotionAction action, MotionSettings original) { this.action = action; this.original = original; }

    public static final class Result {
        public final boolean accepted, terminal;
        public final int completed, required = REQUIRED;
        public final String message;
        public final MotionSettings settings;
        private Result(boolean accepted, boolean terminal, int completed, String message, MotionSettings settings) {
            this.accepted = accepted; this.terminal = terminal; this.completed = completed; this.message = message; this.settings = settings;
        }
    }

    public Result addDemonstration(MotionSequence sequence) {
        if (closed) return new Result(false, true, examples.size(), "This teaching session has ended.", null);
        attempts++;
        try {
            MotionSequence.Shape shape = sequence.shape();
            if (original.conflicts(sequence, shape, action)) throw new IllegalArgumentException("That motion resembles another action. Choose a different motion, or reset the other action first.");
            for (MotionSequence.Shape previous : examples) {
                if (MotionSettings.distance(previous, shape) > .60
                        || shape.durationMs < previous.durationMs * .5 || shape.durationMs > previous.durationMs * 2
                        || shape.peak < previous.peak * .4 || shape.peak > previous.peak * 2.5)
                    throw new IllegalArgumentException("That example was different. Repeat the same direction and general motion.");
            }
            if (examples.size() == REQUIRED - 1) {
                List<MotionSequence.Shape> proposed = new ArrayList<>(examples);
                proposed.add(shape);
                MotionSettings learned = original.withTraining(action, proposed);
                for (MotionSequence demonstration : demonstrations)
                    if (learned.recognize(demonstration) != action)
                        throw new IllegalArgumentException("The examples vary too much at this sensitivity. Repeat with similar strength, timing and direction.");
                if (learned.recognize(sequence) != action)
                    throw new IllegalArgumentException("The examples vary too much at this sensitivity. Repeat with similar strength, timing and direction.");
                examples.add(shape);
                demonstrations.add(sequence);
                closed = true;
                return new Result(true, true, REQUIRED, "Gesture saved.", learned);
            }
            examples.add(shape);
            demonstrations.add(sequence);
            return new Result(true, false, examples.size(), "Example accepted. Hold still, then repeat the same motion.", null);
        } catch (IllegalArgumentException invalid) {
            closed = attempts >= 12;
            return new Result(false, closed, examples.size(), closed ? "Teaching ended after repeated unclear examples. Try again with a distinct motion." : invalid.getMessage(), null);
        }
    }

    public void cancel() { closed = true; }
}
