// Shapes shared with the Python side (spec.py, server.py).

export type Ease = "inOutCubic" | "inOutSine" | "outCubic" | "inCubic" | "outExpo" | "linear" | "hold";
export interface Key { t: number; v: number; ease?: Ease }
export interface Piece { in: number; out: number }
export interface PropDef { group: string; label: string; min: number; max: number; step: number; default: number; log?: boolean }

// A spec as stored (only what differs from the defaults). Loose on purpose:
// the server validates; the editor edits a draft and sends it back.
export type RawSpec = Record<string, any>;

/** What a take shows before the recitation, in this order (spec.INTRO). */
export interface Intro {
  steps: RecordingStep[] | null;
  play: boolean;
  cover: number; home: number; open_by: "tap" | "search"; header: boolean;
  settings: number; roots: number; repeat: boolean; dwell: number;
  goto: "scroll" | "rail" | "jump";
}
export interface RecordingStep {
  kind: "cover" | "home" | "open" | "header" | "settings" | "roots" | "repeat" | "wait";
  seconds: number; via?: "tap" | "search" | "direct"; word?: number;
}

export interface Spec {
  name?: string;
  title: string;
  aspect: string;
  fps: number;
  app: {
    surah: number; ayah: number; reciter: string; theme: string; layout: string; view: string; text_size: string; seconds: number;
    capture: "1080p" | "2k";
    repeat: "off" | "ayah" | "range"; repeat_to: number | null; intro: Intro;
  };
  edit: Piece[];
  background: { id: string; saturation: number; speed: number; parallax: number; vignette: number; offset?: number };
  phone: { color: string; height: number; shadow: number };
  props: Record<string, number>;
  tracks: Record<string, Key[]>;
  motion_blur: number;
  audio: { gain_db: number; fade_in: number; fade_out: number; loudness: number | null };
  duration: number;
}

export interface Meta {
  reciters: Record<string, string>;
  themes: Record<string, { bg: string; gold: string; phone: string }>;
  layouts: string[];
  views: string[];
  aspects: Record<string, [number, number]>;
  eases: Ease[];
  phones: string[];
  defaults: RawSpec;
  props: Record<string, PropDef>;
  backgrounds: Record<string, { title: string; label?: string; suits: string[]; thumb: string }>;
  surahs: { id: number; name: string; ayahs: number }[];
  out_dir: string;
  export: ExportMeta;
  apk: string;
  apk_exists: boolean;
}

export interface PhoneGeometry { W: number; H: number; bw: number; bh: number; body_r: number; sx0: number; sy0: number; sw: number; sh: number; screen_r: number; k: number }
export interface Geo {
  W: number; H: number; fps: number; g: PhoneGeometry;
  take: { length: number; lead: number; capture_fps: number; active_fps?: number; frame_p90_ms?: number; width: number; height: number; recorded: string } | null;
  frame: string; clip: string | null; audio: string | null; peaks: string | null;
  plate: { w: number; h: number; k: number };
}
/** The take's waveform: one 0..255 column (square-root scale) per 1/rate s. */
export interface Wave { rate: number; peaks: Uint8Array }
export interface Plate { url: string; w: number; h: number; k: number; blur: number }
export interface Timeline {
  verses: { label: string; t0: number; t1: number }[];
  repeats: { verse: string; pos: number; t0: number; t1: number }[];
  take: number | null;
}
export interface VersePlan {
  start: number; end: number; anchor: number; shift: number; in: number; out: number; seconds: number;
  verses: Timeline["verses"];
}

export interface SpecStatus {
  name: string; title?: string; aspect?: string; fps?: number; error?: string;
  app?: Spec["app"]; duration?: number; take?: boolean; take_length?: number | null; take_short?: boolean;
  video?: boolean; video_mtime?: number | null; exports?: ExportFile[];
}
export interface ExportFile { file: string; bytes: number; mtime: number; revision: string }

// Export settings (formats.py): what the editor offers and what it sends.
export type ExportFormat = "h264" | "hevc" | "av1" | "vp9" | "prores" | "gif";
export type ExportQuality = "best" | "high" | "balanced" | "small";
export interface ExportOptions { format: ExportFormat; quality: ExportQuality; scale: number; fps: number | null; audio_kbps: number }
export interface ExportFormatInfo {
  label: string; ext: string; audio: "aac" | "opus" | "pcm" | null; hint: string;
  q: Record<ExportQuality, number>; q_label: string; q_names?: Record<ExportQuality, string>;
  rate?: Record<ExportQuality, number>; mbps?: Record<ExportQuality, number>; kbs?: number;
}
export interface ExportMeta {
  formats: Record<ExportFormat, ExportFormatInfo>; qualities: ExportQuality[]; fps: number[]; gif_fps: number[];
  audio_kbps: number[]; scales: number[]; defaults: ExportOptions; h264_high_kbps: number;
}

export type JobState = "queued" | "running" | "done" | "error" | "cancelled";
export interface Job {
  id: string; kind: "capture" | "render" | "emu-boot" | "emu-prepare"; name: string; title: string;
  serial: string | null; state: JobState; stage: { key: string; msg: string } | null;
  stages: { key: string; msg: string; at: number }[]; progress: number; msg: string; log: string[];
  created: number; started: number | null; ended: number | null; result: unknown; error: string | null;
  export_file?: string; export_exists?: boolean;
}
export interface Emulator {
  serial: string; port: number; kind: "capture" | "pool"; booted: boolean; app: boolean;
  cores: number | null; size: string | null; error: string | null; job: Job | null; ready: boolean;
}

export type Selection =
  | { type: "keys"; items: { prop: string; t: number }[] }
  | { type: "piece"; i: number }
  | null;

// The camera, phone and background at one instant: every animatable property.
export type State = Record<string, number>;
