/** @jsxImportSource @revideo/2d/lib */
// "Every word, as it is recited" — 31.5 s Instagram intro (docs/KEY_ART.md).
// All clips are conformed to one timeline (clip t=0 is A0 seconds before the
// recitation), so every cut lands on a verse boundary with no audio edit.
import {Audio, Circle, Img, Node, Rect, Txt, Video, makeScene2D} from '@revideo/2d';
import {Reference, all, createRef, easeInOutCubic, easeOutCubic, linear, useThread, waitFor} from '@revideo/core';

// wait until an absolute scene time (seconds)
function* until(t: number) {
  const now = useThread().time();
  if (t > now) yield* waitFor(t - now);
}

const A0 = 3.0;                                 // the chapter header holds the screen, then the recitation
const V = [6.03, 4.56, 4.74, 5.47, 4.88];       // basmalah, 55:1-4 (EveryAyah Alafasy)
const at = (i: number) => A0 + V.slice(0, i).reduce((s, x) => s + x, 0);
const CUT = {header: 0, royal1: at(1), paper: at(2), mushaf: at(3), royal2: at(4), end: at(5) - 0.4};
const END = 31.5;

const BG = {royal: '#04201A', paper: '#EEE4D1', night: '#08090A'};
const INK = {royal: '#E8E2D5', paper: '#1C1B18', night: '#E8E2D5'};

// Full-bleed header: the 1080x2400 take hung so its status bar, top bar and
// transport fall outside the 1920 frame (rows 200-2120 show). The push-in
// pivots on the chapter rosette (take y ~546 -> canvas y -614).
const HEADER_TOP_CUT = 200, ROSETTE_Y = 546 - HEADER_TOP_CUT - 960;

const SW = 666, SH = 1480;                      // phone screen (1080x2400 takes at 0.617x)

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
  const headerWrap = createRef<Node>(), header = createRef<Video>();
  const phones = {
    royal: {video: createRef<Video>(), group: createRef<Node>()},
    paper: {video: createRef<Video>(), group: createRef<Node>()},
    mushaf: {video: createRef<Video>(), group: createRef<Node>()},
  };
  const cap = createRef<Txt>();
  const card = createRef<Node>(), icon = createRef<Img>(), black = createRef<Rect>();
  const audio = createRef<Audio>();

  view.add(<Rect ref={bg} width={1080} height={1920} fill={BG.royal} />);
  view.add(
    <Node ref={headerWrap} y={ROSETTE_Y}>
      <Video ref={header} src={'/clips/header.mp4'} width={1080} height={2400}
             y={-960 - HEADER_TOP_CUT + 1200 - ROSETTE_Y} />
    </Node>,
  );
  view.add(<Phone clip={'/clips/royal.mp4'} refs={phones.royal} />);
  view.add(<Phone clip={'/clips/paper.mp4'} refs={phones.paper} />);
  view.add(<Phone clip={'/clips/mushaf.mp4'} refs={phones.mushaf} />);
  view.add(<Txt ref={cap} y={-735} width={940} textAlign={'center'} textWrap fontFamily={'Cormorant Italic'}
                fontSize={68} lineHeight={86} fill={INK.royal} opacity={0} />);
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
  header().play();
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

  // 1 — the chapter header holds the screen, then the basmalah writes itself
  yield* all(
    black().opacity(0, 1.0, easeInOutCubic),
    headerWrap().scale(1.08, CUT.royal1, easeInOutCubic),
  );
  // 2 — the phone, as Ar-Rahman begins
  yield* until(CUT.royal1);
  headerWrap().opacity(0);
  show('royal');
  phones.royal.group().scale(1.10);
  yield* all(
    phones.royal.group().scale(1.0, 3.6, easeOutCubic),
    (function* () { yield* waitFor(0.4); yield* caption('Every word, as it is recited.', INK.royal, 2.6); })(),
  );
  // 3 — paper
  yield* until(CUT.paper);
  bg().fill(BG.paper);
  show('paper');
  yield* all(
    phones.paper.group().scale(1.02, 4.7, linear),
    (function* () { yield* waitFor(0.35); yield* caption('Its meaning, word by word.', INK.paper, 3.0); })(),
  );
  // 4 — the printed page: Mushaf, nightfall
  yield* until(CUT.mushaf);
  bg().fill(BG.night);
  show('mushaf');
  yield* all(
    phones.mushaf.group().scale(1.03, 5.4, linear),
    (function* () { yield* waitFor(0.35); yield* caption('Or the whole page, as printed.', INK.night, 3.7); })(),
  );
  // 5 — royal again, a slow push toward the verse
  yield* until(CUT.royal2);
  bg().fill(BG.royal);
  show('royal');
  phones.royal.group().scale(1.0);
  yield* all(
    phones.royal.group().scale(1.05, CUT.end - CUT.royal2, linear),
    (function* () { yield* waitFor(0.35); yield* caption('Free and open source.', INK.royal, 2.8); })(),
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
