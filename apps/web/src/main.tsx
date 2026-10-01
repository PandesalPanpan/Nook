import React from 'react';
import ReactDOM from 'react-dom/client';
import '@fontsource/inter/400.css';
import '@fontsource/inter/500.css';
import '@fontsource/inter/700.css';
import './styles.css';
import { Bootstrap, initializeRuntime } from './bootstrap';
void initializeRuntime((import.meta as ImportMeta & {env:Record<string,string|undefined>}).env);
ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode><Bootstrap /></React.StrictMode>);
