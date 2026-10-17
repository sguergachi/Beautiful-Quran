/** @jsxImportSource @revideo/2d/lib */
// Final cut: 18.6 s, hook in the first frame (docs/KEY_ART.md). Every clip's
// t=0 is the first syllable of the recitation; the voice runs unbroken.
import {Audio, Circle, Gradient, Img, Node, Rect, Txt, Video, makeScene2D} from '@revideo/2d';
import {Reference, all, createRef, easeInOutCubic, easeOutCubic, linear, useThread, waitFor} from '@revideo/core';

function* until(t: number) {
  const now = useThread().time();
  if (t > now) yield* waitFor(t - now);
}

const V = [6.03, 4.56, 4.74, 5.47];             // basmalah, 55:1-3
const at = (i: number) => V.slice(0, i).reduce((s, x) => s + x, 0);
const T = {
  royal: at(1),                                 // 55:1: the phone
  paperFlip: at(1) + 2.3,                       // mid-word theme flip
  nightFlip: at(2),                             // 55:2
  mushaf: at(2) + 2.3,
  card: at(3) + 0.1,                            // 55:3 plays under the end card
  end: 18.6,
};
const BG = {royal: '#04201A', paper: '#EEE4D1', night: '#08090A'};
const INK = {royal: '#E8E2D5', paper: '#1C1B18', night: '#E8E2D5'};
const SW = 666, SH = 1480;

// opener take (1080x2400), full-bleed at 1:1: rows 100-2020 fill the frame
// (status bar above, reciter line and transport below); the push pivots on
// the basmalah (take y ~540 -> canvas y -520)
const OPEN_TOP_CUT = 100, BASMALAH_Y = 540 - OPEN_TOP_CUT - 960;

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

export default makeScene2D('cut', function* (view) {
  const bg = createRef<Rect>();
  const hdrWrap = createRef<Node>(), hdr = createRef<Video>();
  const phones = {
    royal: {video: createRef<Video>(), group: createRef<Node>()},
    paper: {video: createRef<Video>(), group: createRef<Node>()},
    night: {video: createRef<Video>(), group: createRef<Node>()},
    mushaf: {video: createRef<Video>(), group: createRef<Node>()},
  };
  const cap = createRef<Txt>(), hook = createRef<Txt>(), scrim = createRef<Rect>();
  const card = createRef<Node>(), icon = createRef<Img>(), black = createRef<Rect>();
  const audio = createRef<Audio>();

  view.add(<Rect ref={bg} width={1080} height={1920} fill={BG.royal} />);
  // 1 — the app itself, full-bleed: the chapter's ornament band, the basmalah
  // writing itself beneath it
  view.add(
    <Node ref={hdrWrap} y={BASMALAH_Y}>
      <Video ref={hdr} src={'/clips/cut/opener.mp4'} width={1080} height={2400}
             y={-960 - OPEN_TOP_CUT + 1200 - BASMALAH_Y} />
    </Node>,
  );
  // the hook line sits low, on a scrim over the faint upcoming verses
  view.add(
    <Rect ref={scrim} y={560} width={1080} height={800} opacity={0}
          fill={new Gradient({fromY: -400, toY: 400, stops: [
            {offset: 0, color: '#04201A00'}, {offset: 0.45, color: '#04201AE6'}, {offset: 1, color: '#04201AF2'}]})} />,
  );
  view.add(<Txt ref={hook} y={430} width={900} textAlign={'center'} textWrap fontFamily={'Cormorant Italic'}
                fontSize={66} lineHeight={84} fill={INK.royal} opacity={0}
                text={'Every word, written as it is recited.'} />);
  view.add(<Phone clip={'/clips/cut/royal.mp4'} refs={phones.royal} />);
  view.add(<Phone clip={'/clips/cut/paper.mp4'} refs={phones.paper} />);
  view.add(<Phone clip={'/clips/cut/m-night.mp4'} refs={phones.night} />);
  view.add(<Phone clip={'/clips/cut/mushaf.mp4'} refs={phones.mushaf} />);
  view.add(<Txt ref={cap} y={-735} width={980} textAlign={'center'} textWrap fontFamily={'Cormorant Italic'}
                fontSize={70} lineHeight={88} fill={INK.royal} opacity={0} />);
  view.add(
    <Node ref={card} opacity={0}>
      <Img ref={icon} src={'/icon.png'} width={300} height={300} radius={66} y={-250} />
      <Txt y={20} fontFamily={'Cormorant SemiBold'} fontSize={112} fill={'#E8E2D5'} text={'Beautiful Quran'} />
      <Txt y={132} fontFamily={'Junicode'} fontSize={40} fill={'#D9B44A'} letterSpacing={3}
           text={'FREE  ·  ANDROID & WEB'} />
      <Txt y={206} fontFamily={'Cormorant Italic'} fontSize={48} fill={'#E8E2D5CC'} text={'Link in bio'} />
    </Node>,
  );
  view.add(<Rect ref={black} width={1080} height={1920} fill={'#000'} opacity={0} />);
  view.add(<Audio ref={audio} src={'/audio/cut.wav'} />);

  audio().play();
  hdr().play();
  for (const p of Object.values(phones)) p.video().play();

  const show = (k: keyof typeof phones | null) => {
    for (const [name, p] of Object.entries(phones)) p.group().opacity(name === k ? 1 : 0);
  };
  const say = function* (text: string, ink: string, hold: number) {
    cap().text(text); cap().fill(ink); cap().opacity(0); cap().y(-718);
    yield* all(cap().opacity(1, 0.35, easeOutCubic), cap().y(-735, 0.35, easeOutCubic));
    yield* waitFor(hold);
    yield* cap().opacity(0, 0.3);
  };

  // hook: the app is on screen and moving from the first frame; the line
  // lands at once and leaves before the cut
  yield* all(
    hdrWrap().scale(1.06, T.royal, easeInOutCubic),
    scrim().opacity(1, 0.4),
    (function* () {
      yield* waitFor(0.25);
      hook().y(450);
      yield* all(hook().opacity(1, 0.4, easeOutCubic), hook().y(430, 0.4, easeOutCubic));
      yield* until(T.royal - 0.6);
      yield* all(hook().opacity(0, 0.4), scrim().opacity(0, 0.5));
    })(),
  );
  // the phone, as Ar-Rahman begins
  yield* until(T.royal);
  hdrWrap().opacity(0);
  show('royal');
  phones.royal.group().scale(1.12);
  yield* phones.royal.group().scale(1.0, 0.9, easeOutCubic);
  // the page changes, the ink keeps writing
  yield* until(T.paperFlip);
  bg().fill(BG.paper); show('paper');
  yield* say('Word by word, with meaning.', INK.paper, 1.6);
  yield* until(T.nightFlip);
  bg().fill(BG.night); show('night');
  // the printed page
  yield* until(T.mushaf);
  show('mushaf');
  phones.mushaf.group().scale(1.06);
  yield* all(
    phones.mushaf.group().scale(1.0, 0.8, easeOutCubic),
    say('Or the whole page, as printed.', INK.night, 1.8),
  );
  // end card while 55:3 is recited
  yield* until(T.card);
  bg().fill(BG.royal);
  yield* all(
    phones.mushaf.group().opacity(0, 0.4),
    card().opacity(1, 0.5, easeOutCubic),
    icon().scale(0.9, 0).to(1, 1.6, easeOutCubic),
  );
  yield* until(T.end - 0.45);
  yield* black().opacity(1, 0.45);
});
