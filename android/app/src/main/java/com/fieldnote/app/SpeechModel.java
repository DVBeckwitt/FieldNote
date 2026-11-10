package com.fieldnote.app;

enum SpeechModel {
    SMALL("vosk-model-small-en-us-0.15", "Small 0.15",
            "Bundled U.S. English model for phones.", 70_898_967L),
    LGRAPH("vosk-model-en-us-0.22-lgraph", "LGraph 0.22",
            "U.S. English · 131 MB download · 214 MB installed.", 214_028_616L),
    FULL("vosk-model-en-us-0.22", "Full 0.22",
            "U.S. English · 1.91 GB download · 2.87 GB installed. Requires substantial memory.", 2_865_977_882L),
    GIGASPEECH("vosk-model-en-us-0.42-gigaspeech", "GigaSpeech 0.42",
            "U.S. English · 2.42 GB download · 4.03 GB installed. Requires substantial memory.", 4_030_990_765L);

    final String id;
    final String label;
    final String description;
    final String downloadUrl;
    final long installedBytes;

    SpeechModel(String id, String label, String description, long installedBytes) {
        this.id = id;
        this.label = label;
        this.description = description;
        this.installedBytes = installedBytes;
        this.downloadUrl = "vosk-model-small-en-us-0.15".equals(id) ? null
                : "https://alphacephei.com/vosk/models/" + id + ".zip";
    }

    static SpeechModel fromId(String id) {
        for (SpeechModel model : values()) {
            if (model.id.equals(id)) return model;
        }
        return null;
    }

    boolean isLarge() {
        return this == FULL || this == GIGASPEECH;
    }
}
