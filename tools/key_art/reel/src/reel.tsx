/** @jsxImportSource @revideo/2d/lib */
// "Every word, as it is recited" — 29 s Instagram intro (docs/KEY_ART.md).
// All clips are conformed to one timeline (clip t=0 is 0.6 s before the
// recitation), so every cut lands on a verse boundary with no audio edit.
import {Audio, Circle, Img, Node, Rect, Txt, Video, makeScene2D} from '@revideo/2d';
import {
  Reference, all, createRef, easeInOutCubic, easeOutCubic, linear, useThread, waitFor,
} from '@revideo/core';

// wait until an absolute scene time (seconds)
function* until(t: number) {
  const now = useThread().time();
  if (t > now) yield* waitFor(t - now);
}
// The full-bleed opener: the screen at 1.5x (720 -> 1080) hung from the top of
// the frame; the push-in pivots on the basmalah (clip y ~187 -> canvas y -680).
const MACRO_PIVOT = -680;

const A0 = 0.6;                                 // recitation starts here
const V = [6.03, 4.56, 4.74, 5.47, 4.88];       // basmalah, 55:1-4 (EveryAyah Alafasy)
const at = (i: number) => A0 + V.slice(0, i).reduce((s, x) => s + x, 0);
const CUT = {macro: 0, royal1: at(1), paper: at(2), night: at(3), royal2: at(4), end: at(5) - 0.4};
const END = 29.0;

const THEMES = {
  royal: {bg: ['#0B3A30', '#04201A'], ink: '#E8E2D5', accent: '#D9B44A'},
  paper: {bg: ['#F6EFE3', '#EEE4D1'], ink: '#1C1B18', accent: '#0E5C4A'},
  night: {bg: ['#1A1B1E', '#08090A'], ink: '#E8E2D5', accent: '#D9B44A'},
};

const SW = 666, SH = 1480;                      // phone screen (720x1600 clips at 0.925x)

function Phone({clip, refs}: {clip: string; refs: {video: Reference<Video>; group: Reference<Node>}}) {
  const R = 64, BEZ = 15;
  return (
    <Node ref={refs.group} y={112} opacity={0}>
      <Rect width={SW + BEZ * 2 + 10} height={SH + BEZ * 2 + 10} radius={R + BEZ + 5} fill={'#00000000'}
            shadowColor={'#00000088'} shadowBlur={90} shadowOffset={[0, 40]} />
      <Rect width={SW + BEZ * 2 + 10} height={SH + BEZ * 2 + 10} radius={R + BEZ + 5} fill={'#A9ABAF'} />
      <Rect width={SW + BEZ * 2 + 4} height={SH + BEZ * 2 + 4} radius={R + BEZ + 2} fill={'#5E6064'} />
      <Rect width={SW + BEZ * 2} height={SH + BEZ * 2} radius={R + BEZ} fill={'#070708'} />
      <Rect width={SW} height={SH} radius={R} clip>
        <Video ref={refs.video} src={clip} width={SW} height={SH} />
      </Rect>
      <Circle y={-SH / 2 + 28} size={22} fill={'#040405'} />
      <Rect x={SW / 2 + BEZ + 5} y={-SH * 0.29} width={7} height={SH * 0.07} radius={3} fill={'#96989C'} />
      <Rect x={SW / 2 + BEZ + 5} y={-SH * 0.14} width={7} height={SH * 0.12} radius={3} fill={'#96989C'} />
    </Node>
  );
}

export default makeScene2D('reel', function* (view) {
  const bg = createRef<Rect>();
  const macroWrap = createRef<Node>(), macro = createRef<Video>();
  const phones = {
    royal: {video: createRef<Video>(), group: createRef<Node>()},
    paper: {video: createRef<Video>(), group: createRef<Node>()},
    night: {video: createRef<Video>(), group: createRef<Node>()},
  };
  const cap = createRef<Txt>();
  const card = createRef<Node>(), icon = createRef<Img>(), black = createRef<Rect>();
  const audio = createRef<Audio>();

  view.add(<Rect ref={bg} width={1080} height={1920} fill={THEMES.royal.bg[1]} />);
  view.add(
    <Node ref={macroWrap} y={MACRO_PIVOT}>
      <Video ref={macro} src={'/clips/macro.mp4'} width={1080} height={2400} y={240 - MACRO_PIVOT} />
    </Node>,
  );
  view.add(<Phone clip={'/clips/royal.mp4'} refs={phones.royal} />);
  view.add(<Phone clip={'/clips/paper.mp4'} refs={phones.paper} />);
  view.add(<Phone clip={'/clips/night.mp4'} refs={phones.night} />);
  view.add(<Txt ref={cap} y={-735} width={940} textAlign={'center'} textWrap fontFamily={'Cormorant Italic'}
                fontSize={68} lineHeight={86} fill={THEMES.royal.ink} opacity={0} />);
  view.add(
    <Node ref={card} opacity={0}>
      <Img ref={icon} src={'/icon.png'} width={300} height={300} radius={66} y={-240} />
      <Txt y={20} fontFamily={'Cormorant SemiBold'} fontSize={108} fill={'#E8E2D5'} text={'Beautiful Quran'} />
      <Txt y={130} fontFamily={'Junicode'} fontSize={40} fill={'#D9B44A'} letterSpacing={3}
           text={'ANDROID  ·  WEB  ·  LINK IN BIO'} />
      <Txt y={200} fontFamily={'Cormorant Italic'} fontSize={44} fill={'#E8E2D5BB'} text={'Free and open source'} />
    </Node>,
  );
  view.add(<Rect ref={black} width={1080} height={1920} fill={'#000'} opacity={1} />);
  view.add(<Audio ref={audio} src={'/audio/reel.wav'} />);

  // all footage rolls together from t=0
  audio().play();
  macro().play();
  for (const p of Object.values(phones)) p.video().play();

  const show = (k: keyof typeof phones) => {
    for (const [name, p] of Object.entries(phones)) p.group().opacity(name === k ? 1 : 0);
  };
  const caption = function* (text: string, ink: string, hold: number) {
    cap().text(text); cap().fill(ink); cap().y(-715);
    yield* all(cap().opacity(1, 0.7, easeOutCubic), cap().y(-735, 0.7, easeOutCubic));
    yield* waitFor(hold);
    yield* cap().opacity(0, 0.5);
  };

  // 1 — macro: the basmalah writes itself, slow push-in
  macroWrap().scale(1.0);
  yield* all(
    black().opacity(0, 0.6),
    macroWrap().scale(1.12, CUT.royal1, easeInOutCubic),
  );
  // 2 — royal phone settles in as Ar-Rahman begins
  yield* until(CUT.royal1);
  macroWrap().opacity(0);
  bg().fill(THEMES.royal.bg[1]);
  show('royal');
  phones.royal.group().scale(1.10);
  yield* all(
    phones.royal.group().scale(1.0, 3.6, easeOutCubic),
    (function* () { yield* waitFor(0.4); yield* caption('Every word, as it is recited.', THEMES.royal.ink, 2.6); })(),
  );
  // 3 — paper
  yield* until(CUT.paper);
  bg().fill(THEMES.paper.bg[1]);
  show('paper');
  yield* all(
    phones.paper.group().scale(1.02, 4.7, linear),
    (function* () { yield* waitFor(0.35); yield* caption('Its meaning, word by word, beneath.', THEMES.paper.ink, 3.0); })(),
  );
  // 4 — nightfall
  yield* until(CUT.night);
  bg().fill(THEMES.night.bg[1]);
  show('night');
  yield* all(
    phones.night.group().scale(1.02, 5.4, linear),
    (function* () { yield* waitFor(0.35); yield* caption('Paper, nightfall, or royal green.', THEMES.night.ink, 3.7); })(),
  );
  // 5 — royal again, a slow push toward the verse
  yield* until(CUT.royal2);
  bg().fill(THEMES.royal.bg[1]);
  show('royal');
  phones.royal.group().scale(1.0);
  yield* all(
    phones.royal.group().scale(1.05, CUT.end - CUT.royal2, linear),
    (function* () { yield* waitFor(0.35); yield* caption('Free and open source.', THEMES.royal.ink, 2.8); })(),
  );
  // 6 — end card
  yield* all(
    phones.royal.group().opacity(0, 0.7, easeInOutCubic),
    card().opacity(1, 0.9, easeInOutCubic),
    icon().scale(0.94, 0).to(1, 2.4, easeOutCubic),
  );
  yield* until(END - 0.5);
  yield* black().opacity(1, 0.5);
});

