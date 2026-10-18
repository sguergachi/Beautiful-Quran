import {makeProject} from '@revideo/core';
import './fonts.css';
import mockup from './mockup?scene';
export default makeProject({ scenes: [mockup], settings: { shared: { size: {x: 1080, y: 1920}, background: '#000000' }, rendering: { fps: 60 } } });
