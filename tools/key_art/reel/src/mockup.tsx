/** @jsxImportSource @revideo/2d/lib */
// Full-length device mockup: one theme, basmalah + 55:1-10, 60 fps.
import {Audio, Circle, Node, Rect, Txt, Video, makeScene2D} from '@revideo/2d';
import {all, createRef, easeInOutCubic, useScene, useThread, waitFor} from '@revideo/core';

const THEMES: Record<string, {bg: string; ink: string}> = {
  royal: {bg: '#06271F', ink: '#E8E2D5'},
  paper: {bg: '#EEE4D1', ink: '#1C1B18'},
  night: {bg: '#0B0C0E', ink: '#E8E2D5'},
};
const SW = 720, SH = 1600, R = 70, BEZ = 16;

export default makeScene2D('mockup', function* (view) {
  const theme = useScene().variables.get('theme', 'royal')();
  const clip = useScene().variables.get('clip', '/clips/m-royal.mp4')();
  const dur = useScene().variables.get('dur', 68.9)();
  const t = THEMES[theme as string];
  const v = createRef<Video>(), a = createRef<Audio>(), black = createRef<Rect>(), mark = createRef<Txt>();
  view.add(<Rect width={1080} height={1920} fill={t.bg} />);
  view.add(
    <Node y={-34} scale={0.98}>
      <Rect width={SW + BEZ * 2 + 10} height={SH + BEZ * 2 + 10} radius={R + BEZ + 5} fill={'#00000000'}
            shadowColor={'#00000080'} shadowBlur={100} shadowOffset={[0, 44]} />
      <Rect width={SW + BEZ * 2 + 10} height={SH + BEZ * 2 + 10} radius={R + BEZ + 5} fill={'#A9ABAF'} />
      <Rect width={SW + BEZ * 2 + 4} height={SH + BEZ * 2 + 4} radius={R + BEZ + 2} fill={'#5E6064'} />
      <Rect width={SW + BEZ * 2} height={SH + BEZ * 2} radius={R + BEZ} fill={'#070708'} />
      <Rect width={SW} height={SH} radius={R} clip>
        <Video ref={v} src={clip as string} width={SW} height={SH} />
      </Rect>
      <Circle y={-SH / 2 + 30} size={24} fill={'#040405'} />
    </Node>,
  );
  view.add(<Txt ref={mark} y={872} fontFamily={'Cormorant Italic'} fontSize={44} fill={t.ink} opacity={0.0}
                text={'Beautiful Quran'} />);
  view.add(<Rect ref={black} width={1080} height={1920} fill={'#000'} />);
  view.add(<Audio ref={a} src={'/audio/mockup.wav'} />);
  a().play(); v().play();
  yield* all(black().opacity(0, 0.6), mark().opacity(0.55, 1.2, easeInOutCubic));
  const now = useThread().time();
  yield* waitFor((dur as number) - 0.6 - now);
  yield* black().opacity(1, 0.6);
});
