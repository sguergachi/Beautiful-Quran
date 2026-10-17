import {makeProject} from '@revideo/core';
import './fonts.css';
import cut from './cut?scene';
export default makeProject({ scenes: [cut], settings: { shared: { size: {x: 1080, y: 1920}, background: '#000000' }, rendering: { fps: 60 } } });
