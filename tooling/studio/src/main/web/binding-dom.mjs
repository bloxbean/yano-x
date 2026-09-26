/**
 * Minimal DOM builders for the binding editor. Content is only ever assigned as text or as attribute values of
 * Studio's own choosing; nothing is parsed as markup, so document, catalog and report text cannot become HTML.
 * Every displayed text, accessible name and title also escapes characters that can hide or reorder text (bidi
 * controls, invisible and format characters), so an untrusted name always reads as what it is. Input values are
 * not display text and stay exact.
 */
import {isHidden} from './binding-explain.mjs';

/** Escapes hidden characters for display, keeping line breaks and tabs of multi-line text. */
export function displayText(text) {
  let result = '';
  for (const character of String(text)) {
    result += character !== '\n' && character !== '\t' && isHidden(character)
      ? `\\u{${character.codePointAt(0).toString(16).padStart(4, '0')}}` : character;
  }
  return result;
}

const DISPLAYED_ATTRIBUTES = new Set(['aria-label', 'title', 'placeholder']);
export function h(tag, attributes = {}, ...children) {
  const element = document.createElement(tag);
  for (const [key, value] of Object.entries(attributes)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') element.className = value;
    else if (key === 'text') element.textContent = displayText(value);
    else if (key === 'value') element.value = value;
    else if (key === 'checked' || key === 'selected' || key === 'disabled' || key === 'hidden'
      || key === 'readOnly' || key === 'required') element[key] = Boolean(value);
    else if (key.startsWith('on')) element.addEventListener(key.slice(2), value);
    else if (key === 'dataset') Object.assign(element.dataset, value);
    else element.setAttribute(key, value === true ? '' : DISPLAYED_ATTRIBUTES.has(key) ? displayText(value) : String(value));
  }
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    element.append(child instanceof Node ? child : displayText(child));
  }
  return element;
}

/** Replaces an element's children, skipping absent optional parts (DOM `replaceChildren` would print "null"). */
export function put(element, ...children) {
  element.replaceChildren(...children.flat(Infinity).filter(child => child !== null && child !== undefined && child !== false)
    .map(child => child instanceof Node ? child : displayText(child)));
}

const SVG = 'http://www.w3.org/2000/svg';
export function svg(tag, attributes = {}, ...children) {
  const element = document.createElementNS(SVG, tag);
  for (const [key, value] of Object.entries(attributes)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'text') element.textContent = displayText(value);
    else if (key.startsWith('on')) element.addEventListener(key.slice(2), value);
    else if (key === 'dataset') Object.assign(element.dataset, value);
    else element.setAttribute(key, DISPLAYED_ATTRIBUTES.has(key) ? displayText(value) : String(value));
  }
  for (const child of children.flat(Infinity)) if (child) element.append(child);
  return element;
}
