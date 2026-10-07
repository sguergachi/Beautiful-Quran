import com.beautifulquran.playback.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.sound.sampled.*;

/** Unlabeled execution/decision-cost audit. Does not measure reader eligibility or live sync. */
public final class DetectorAudit {
    static final int WARMUP = 5, RUNS = 10;
    static final List<Long> extraction = new ArrayList<>();
    static final EnumMap<TarjiDetectorMode, List<Long>> timings = new EnumMap<>(TarjiDetectorMode.class);
    static final List<Double> recordingMs = new ArrayList<>();
    static final List<Double> recordingAudioMs = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        Files.createDirectories(output);
        for (var mode : TarjiDetectorMode.values()) timings.put(mode, new ArrayList<>());
        var knobs = new Tarji();
        String settings = "UNLABELED execution only; warmup=" + WARMUP + "; measured_runs=" + RUNS
            + "; minHz=" + knobs.getMinTremoloHz() + "; maxHz=" + knobs.getMaxTremoloHz()
            + "; holdMs=" + knobs.getHoldMinMs() + "; depth=" + knobs.getMinTremoloDepth()
            + "; volume=" + knobs.getMinVolume() + "; periodicity=" + knobs.getMinPeriodicity()
            + "; JVM=" + System.getProperty("java.version") + "; os=" + System.getProperty("os.name")
            + "; arch=" + System.getProperty("os.arch");
        Files.writeString(output.resolve("detector-audit-settings.txt"), settings + "\n");
        var rows = new StringBuilder("file,audio_sha256,mode,hops,audio_ms,voiced_hops,fresh_f0_hops,active_hops,gain_gt_0_01_hops,events,first_event_ms,decision_p50_us,decision_p95_us,recording_median_ms,calibrated,volume_floor\n");
        var baseline = new StringBuilder();
        try (var files = Files.list(input)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".wav")).sorted().toList()) {
                float[][] pcm = read(file);
                String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
                List<TarjiFrame> frames = new ArrayList<>();
                var current = new Tarji();
                var digest = MessageDigest.getInstance("SHA-256");
                var values = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
                var stats = new Stats();
                for (float[] hop : pcm) {
                    current.onSamples8k(hop, hop.length);
                    var f = current.getMeasurements$app();
                    frames.add(f.copy(f.getHop(), f.getHopMs(), f.getHopRms(), f.getRms80(), f.getLevel(),
                        f.getVoiced(), f.getHoldMs(), f.getHoldStartHop(), f.getHoldPitchHz(), f.getHoldClarity(),
                        f.getF0Hz(), f.getF0Valid(), f.getPitchQuality(), f.getPitchLeadHops()));
                    stats.add(current.getReverberating(), current.getTremoloGain(), current.getEventStartHop());
                    values.clear();
                    values.putFloat(current.getTremolo()).putFloat(current.getTremoloGain())
                        .putFloat(current.getHoldMs()).putFloat(current.getLastRateHz())
                        .putInt(current.getEventStartHop()).putInt(current.getReverberating() ? 1 : 0);
                    digest.update(values.array());
                }
                baseline.append(file.getFileName()).append(',').append(stats.active).append(',')
                    .append(HexFormat.of().formatHex(digest.digest())).append('\n');
                int voiced = (int) frames.stream().filter(TarjiFrame::getVoiced).count();
                int pitch = (int) frames.stream().filter(TarjiFrame::getF0Valid).count();
                var times = new ArrayList<Long>();
                // Extractor plus unchanged baseline decision; PCM preparation and snapshots excluded.
                for (int run = -WARMUP; run < RUNS; run++) {
                    current.reset();
                    for (float[] hop : pcm) {
                        long start = System.nanoTime();
                        current.onSamples8k(hop, hop.length);
                        long elapsed = System.nanoTime() - start;
                        if (run >= 0) times.add(elapsed);
                    }
                }
                extraction.addAll(times);
                row(rows, file, sha, TarjiDetectorMode.Current, pcm.length, voiced, pitch, stats, times, Double.NaN, false, knobs.getMinVolume());
                for (var mode : new TarjiDetectorMode[]{TarjiDetectorMode.Cycles, TarjiDetectorMode.Spectrum}) {
                    var experiment = new TarjiExperimentalDetector();
                    var measured = new ArrayList<Long>();
                    var result = new Stats();
                    for (int run = -WARMUP; run < RUNS; run++) {
                        experiment.clear(0);
                        for (var frame : frames) {
                            long start = System.nanoTime();
                            experiment.next(frame, mode, knobs);
                            long elapsed = System.nanoTime() - start;
                            if (run >= 0) measured.add(elapsed);
                            if (run == 0) {
                                var d = experiment.getDecision();
                                result.add(d.getReverberating(), d.getGain(), d.getEventStartHop());
                            }
                        }
                    }
                    timings.get(mode).addAll(measured);
                    row(rows, file, sha, mode, pcm.length, voiced, pitch, result, measured, Double.NaN, false, knobs.getMinVolume());
                }
                var offlineTimes = new ArrayList<Long>();
                TarjiRecordingResult recording = null;
                for (int run = -WARMUP; run < RUNS; run++) {
                    long start = System.nanoTime();
                    var result = TarjiRecordingDetector.INSTANCE.analyze(frames, knobs, () -> true);
                    long elapsed = System.nanoTime() - start;
                    if (run >= 0) offlineTimes.add(elapsed);
                    if (run == 0) recording = result;
                }
                var offlineStats = new Stats();
                for (int i = 0; i < pcm.length; i++) offlineStats.add(recording.getEventStart()[i] >= 0,
                    recording.getGain()[i], recording.getEventStart()[i]);
                double ms = percentile(offlineTimes, 0.5) / 1e6;
                recordingMs.add(ms); recordingAudioMs.add(pcm.length * 20.0);
                row(rows, file, sha, TarjiDetectorMode.Recording, pcm.length, voiced, pitch, offlineStats, List.of(), ms,
                    recording.getCalibrated(), recording.getVolumeFloor());
            }
        }
        Files.writeString(output.resolve("detector-audit.csv"), rows);
        Files.writeString(output.resolve("baseline-after.csv"), baseline);
        var summary = new StringBuilder("metric,p50_us,p95_us\n");
        summary.append("extractor_plus_Current,").append(percentile(extraction, .5) / 1000.0).append(',').append(percentile(extraction, .95) / 1000.0).append('\n');
        for (var mode : new TarjiDetectorMode[]{TarjiDetectorMode.Cycles, TarjiDetectorMode.Spectrum}) {
            summary.append(mode).append("_added_decision,").append(percentile(timings.get(mode), .5) / 1000.0).append(',')
                .append(percentile(timings.get(mode), .95) / 1000.0).append('\n');
        }
        double totalMs = recordingMs.stream().mapToDouble(Double::doubleValue).sum();
        double audioMs = recordingAudioMs.stream().mapToDouble(Double::doubleValue).sum();
        summary.append("Recording_postfeature_us_per_audio_minute,").append(totalMs * 1000 * 60000 / audioMs).append(",NaN\n");
        Files.writeString(output.resolve("detector-audit-performance.csv"), summary);
        System.out.println(settings);
        System.out.println(summary);
    }

    static float[][] read(Path path) throws Exception {
        try (var audio = AudioSystem.getAudioInputStream(path.toFile())) {
            var f = audio.getFormat();
            if (f.getSampleRate() != 8000 || f.getChannels() != 1 || f.getSampleSizeInBits() != 16 || f.isBigEndian()
                || f.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) throw new IllegalArgumentException("Expected mono 8k PCM16: " + path);
            byte[] bytes = audio.readAllBytes();
            var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            var pcm = new float[bytes.length / 320][160];
            for (var hop : pcm) for (int i = 0; i < hop.length; i++) hop[i] = input.getShort() / 32768f;
            return pcm;
        }
    }

    static final class Stats {
        int active, gain, first = -1;
        Set<Integer> events = new HashSet<>();
        void add(boolean accepted, float level, int event) {
            if (accepted) active++;
            if (level > .01f) gain++;
            if (event >= 0) { events.add(event); if (first < 0) first = event; }
        }
    }

    static long percentile(List<Long> values, double q) {
        if (values.isEmpty()) return -1;
        var sorted = new ArrayList<>(values); Collections.sort(sorted);
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(q * sorted.size())));
    }

    static void row(StringBuilder out, Path file, String sha, TarjiDetectorMode mode, int hops, int voiced, int pitch,
                    Stats s, List<Long> times, double offlineMs, boolean calibrated, float floor) {
        out.append(String.format("%s,%s,%s,%d,%.1f,%d,%d,%d,%d,%d,%.1f,%.3f,%.3f,%.3f,%s,%.6f%n",
            file.getFileName(), sha, mode, hops, hops * 20.0, voiced, pitch, s.active, s.gain, s.events.size(),
            s.first < 0 ? -1.0 : s.first * 20.0, times.isEmpty() ? Double.NaN : percentile(times, .5) / 1000.0,
            times.isEmpty() ? Double.NaN : percentile(times, .95) / 1000.0, offlineMs, calibrated, floor));
    }
}
