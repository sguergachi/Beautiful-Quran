import {makeProject} from '@revideo/core';
import './fonts.css';
import reel from './reel?scene';
export default makeProject({ scenes: [reel], settings: { shared: { size: {x: 1080, y: 1920}, background: '#000000' }, rendering: { fps: 60 } } });
