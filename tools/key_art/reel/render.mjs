// node render.mjs PROJECT.ts OUT.mp4 ['{"var":"value"}']
import {renderVideo} from '@revideo/renderer';
const [project, out, vars] = process.argv.slice(2);
const file = await renderVideo({
  projectFile: project,
  variables: vars ? JSON.parse(vars) : {},
  settings: {
    outFile: out, outDir: './output', logProgress: false, workers: 1,
    puppeteer: { executablePath: '/usr/bin/google-chrome-stable', args: ['--no-sandbox', '--autoplay-policy=no-user-gesture-required'] },
    // ProRes master; delivery encodes are done by finish.sh at our own quality
    projectSettings: { exporter: { name: '@revideo/core/ffmpeg', options: { format: out.endsWith('.mov') ? 'proRes' : 'mp4' } } },
  },
});
console.log('rendered', file);
