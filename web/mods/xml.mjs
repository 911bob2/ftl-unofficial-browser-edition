// FTL's sloppy XML as ftlman 0.7.4 reads and writes it: a port of speedy-xml 0.1.1 (RapidXML-like reader, writer,
// escaping) and ftlman's xmltree builder/emitter. Bug-for-bug where it changes output (see docs/MODS.md):
// unescape drops text before an unresolved '&' once another entity resolves, attribute values are written with
// text escaping (a '"' stays raw), comments escape '>', attributes are written sorted and de-duplicated.

export class XmlError extends Error {}

const isWs = c => c === 32 || c === 10 || c === 13 || c === 9;
const lut = chars => { const t = new Uint8Array(128); for (const ch of chars) t[ch.charCodeAt(0)] = 1; return t; };
const INVALID_NAME = lut(' \n\r\t/>?\0:'), INVALID_ATTR = lut(' \n\r\t/<>=?!\0:');
const invalidName = c => c < 128 && INVALID_NAME[c] === 1, invalidAttr = c => c < 128 && INVALID_ATTR[c] === 1;

// ---- escaping -------------------------------------------------------------------------------------------------

function resolveEntity(s, i) {
  // s[i] is the character after '&'. Returns [char, index after ';'] or null.
  const c = s[i];
  let ch;
  if (c === 'l') { if (s[i + 1] !== 't') return null; ch = '<'; i += 2; }
  else if (c === 'g') { if (s[i + 1] !== 't') return null; ch = '>'; i += 2; }
  else if (c === 'a') {
    if (s[i + 1] === 'p') { if (s[i + 2] !== 'o' || s[i + 3] !== 's') return null; ch = "'"; i += 4; }
    else if (s[i + 1] === 'm') { if (s[i + 2] !== 'p') return null; ch = '&'; i += 3; }
    else return null;
  } else if (c === 'q') { if (s[i + 1] !== 'u' || s[i + 2] !== 'o' || s[i + 3] !== 't') return null; ch = '"'; i += 4; }
  else if (c === '#') {
    let code = 0, radix = 10; i++;
    if (i >= s.length) return null;
    if (s[i] === 'x') { radix = 16; i++; }
    for (;;) {
      if (i >= s.length) return null;
      const d = s[i];
      if (d === ';') break;
      const v = parseInt(d, radix);
      if (Number.isNaN(v) || !/^[0-9a-zA-Z]$/.test(d)) return null;
      code = (code * radix + v) >>> 0; i++;
    }
    if (code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) return null;
    return [String.fromCodePoint(code), i + 1];
  } else return null;
  if (s[i] !== ';') return null;
  return [ch, i + 1];
}

/** speedy_xml::escape::unescape, including its loss of text before an unresolved entity. */
export function unescape(string) {
  let replaced = '', cur = 0;
  for (;;) {
    const a = string.indexOf('&', cur), z = string.indexOf('\0', cur);
    const next = a < 0 ? z : z < 0 ? a : Math.min(a, z);
    if (next < 0) break;
    if (string[next] === '&') {
      const r = resolveEntity(string, next + 1);
      if (r) {
        replaced += string.slice(cur, next);
        if (r[0] === '\0') return replaced;
        replaced += r[0]; cur = r[1];
      } else cur += 1;
    } else {
      if (replaced === '') return string;
      return replaced + string.slice(cur, next);
    }
  }
  return replaced === '' ? string : replaced + string.slice(cur);
}

const ESC = {'<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;'};
export const contentEscape = s => /[<&]/.test(s) ? s.replace(/[<&]/g, c => ESC[c]) : s;
export const commentEscape = s => s.includes('>') ? s.replace(/>/g, '&gt;') : s;

// ---- reader ---------------------------------------------------------------------------------------------------

/** Events: {type:'start'|'empty', prefix, name, attrs (raw text after the name), raw} | {type:'end', prefix, name}
 *  | {type:'text', raw} | {type:'cdata'|'comment'|'doctype', content, raw}. Errors throw XmlError. */
export class Reader {
  constructor(text, {topLevelText = false, unmatchedClosing = false, unclosed = false} = {}) {
    this.t = text; this.cur = 0; this.depth = 0; this.topLevelText = topLevelText; this.unmatchedClosing = unmatchedClosing; this.unclosed = unclosed;
  }
  // Next NUL at or after `from`, cached: documents rarely contain one and rescanning per attribute is quadratic.
  nextNul(from) { if (this.nul === undefined || (this.nul !== -1 && this.nul < from)) this.nul = this.t.indexOf('\0', from); return this.nul; }
  fail(message) { this.cur = this.t.length; this.depth = 0; throw new XmlError(message); }
  posOrEnd(start, pred) { const t = this.t; for (let i = start; i < t.length; i++) if (pred(t.charCodeAt(i))) return i; return t.length; }
  skipWs() { const t = this.t; let i = this.cur; while (i < t.length && isWs(t.charCodeAt(i))) i++; this.cur = i; }
  skipAttributes() {
    const t = this.t;
    for (;;) {
      this.skipWs();
      const nameEnd = this.posOrEnd(this.cur, invalidAttr);
      if (nameEnd === this.cur) return;
      this.cur = nameEnd; this.skipWs();
      if (t[this.cur] !== '=') this.fail('expected `=` after attribute name');
      this.cur++; this.skipWs();
      const quote = t[this.cur];
      if (quote !== '"' && quote !== "'") this.fail('expected an attribute value enclosed in either `\'` or `"`');
      this.cur++;
      const q = t.indexOf(quote, this.cur), z = this.nextNul(this.cur), end = q < 0 ? z : z < 0 ? q : Math.min(q, z);
      if (end < 0) this.fail('unclosed attribute value');
      if (t[end] === '\0') this.fail('attribute value contains null byte');
      this.cur = end + 1;
    }
  }
  skipDoctype() {
    const t = this.t;
    for (;;) {
      const g = t.indexOf('>', this.cur), b = t.indexOf('[', this.cur), idx = g < 0 ? b : b < 0 ? g : Math.min(g, b);
      if (idx < 0) this.fail('unexpected end of file in <!DOCTYPE');
      if (t[idx] === '[') {
        this.cur = idx + 1; let depth = 1;
        while (depth > 0) {
          const o = t.indexOf('[', this.cur), c = t.indexOf(']', this.cur), j = o < 0 ? c : c < 0 ? o : Math.min(o, c);
          if (j < 0) this.fail('unexpected end of file in <!DOCTYPE');
          depth += t[j] === '[' ? 1 : -1; this.cur = j + 1;
        }
      } else { this.cur = idx + 1; return; }
    }
  }
  prefixedName(start, prefixEndDefault) {
    const firstEnd = this.posOrEnd(this.cur, invalidName);
    if (firstEnd === this.cur) this.fail('expected element name');
    this.cur = firstEnd;
    if (this.t[this.cur] === ':') {
      const secondEnd = this.posOrEnd(this.cur + 1, invalidName);
      this.cur = secondEnd;
      return [firstEnd, secondEnd];
    }
    return [start + prefixEndDefault, firstEnd];
  }
  parseNode() {
    const t = this.t, start = this.cur;
    this.cur++;
    if (this.cur >= t.length) this.fail('invalid element name');
    const c = t[this.cur];
    if (c === '?') {
      const end = t.indexOf('?>', this.cur);
      if (end < 0) this.fail('unclosed processing instruction');
      this.cur = end + 2; return null;
    }
    if (c === '!') {
      const n = t[this.cur + 1];
      if (n === '-' && t[this.cur + 2] === '-') {
        this.cur += 2;
        const end = t.indexOf('-->', this.cur);
        if (end < 0) this.fail('unclosed comment');
        this.cur = end + 3; const raw = t.slice(start, this.cur);
        return {type: 'comment', raw, content: raw.length >= 7 ? raw.slice(4, -3) : ''};
      }
      if (n === '[' && t.startsWith('CDATA[', this.cur + 2)) {
        this.cur += 8;
        const end = t.indexOf(']]>', this.cur);
        if (end < 0) this.fail('unclosed cdata');
        this.cur = end + 3; const raw = t.slice(start, this.cur);
        return {type: 'cdata', raw, content: raw.slice(9, -3)};
      }
      if (n === 'D' && t.startsWith('OCTYPE', this.cur + 2) && this.cur + 8 < t.length && isWs(t.charCodeAt(this.cur + 8))) {
        this.cur += 9; this.skipDoctype(); const raw = t.slice(start, this.cur);
        return {type: 'doctype', raw, content: raw.slice(10, -1)};
      }
      const end = t.indexOf('>', this.cur + 1);
      if (end < 0) this.fail('unclosed unknown <! tag');
      this.cur = end + 1; return null;
    }
    if (c === '/' && (this.depth > 0 || this.unmatchedClosing)) {
      this.cur++;
      const [prefixEnd, nameEnd] = this.prefixedName(start, 1);
      this.skipWs();
      if (t[this.cur] !== '>') this.fail('expected a `>`');
      this.depth = Math.max(0, this.depth - 1); this.cur++;
      return {type: 'end', prefix: prefixEnd !== start + 1 ? t.slice(start + 2, prefixEnd) : null, name: t.slice(prefixEnd + 1, nameEnd)};
    }
    const [prefixEnd, nameEnd] = this.prefixedName(start, 0);
    this.skipAttributes(); this.skipWs();
    const d = t[this.cur];
    const prefix = prefixEnd > start ? t.slice(start + 1, prefixEnd) : null, name = t.slice(prefixEnd + 1, nameEnd);
    if (d === '>') {
      this.cur++; this.depth++;
      return {type: 'start', prefix, name, attrs: t.slice(nameEnd, this.cur), raw: t.slice(start, this.cur)};
    }
    if (d === '/') {
      if (t[this.cur + 1] !== '>') this.fail('expected a `>`');
      this.cur += 2;
      return {type: 'empty', prefix, name, attrs: t.slice(nameEnd, this.cur), raw: t.slice(start, this.cur)};
    }
    this.fail('expected a `>` or `/`');
  }
  next() {
    const t = this.t;
    for (;;) {
      if (this.cur < t.length) {
        if (t[this.cur] === '<') { const e = this.parseNode(); if (e) return e; continue; }
        let end = t.indexOf('<', this.cur); if (end < 0) end = t.length;
        const from = this.cur; this.cur = end;
        if (this.depth === 0 && !this.topLevelText) {
          for (let i = from; i < end; i++) if (!isWs(t.charCodeAt(i))) { this.cur = from; this.fail('top-level text is forbidden'); }
          continue;
        }
        return {type: 'text', raw: t.slice(from, end)};
      }
      if (this.depth > 0) { if (this.unclosed) return null; this.depth = 0; throw new XmlError('unclosed element'); }
      return null;
    }
  }
}

/** speedy_xml Attributes iterator over an already validated start tag: [{name, raw, quote}] in source order. */
export function attributes(attrText) {
  const out = [], t = attrText; let cur = 0;
  for (;;) {
    while (cur < t.length && isWs(t.charCodeAt(cur))) cur++;
    let nameEnd = cur; while (nameEnd < t.length && !invalidAttr(t.charCodeAt(nameEnd))) nameEnd++;
    if (nameEnd === cur) return out;
    const name = t.slice(cur, nameEnd); cur = nameEnd;
    while (cur < t.length && isWs(t.charCodeAt(cur))) cur++;
    cur++;
    while (cur < t.length && isWs(t.charCodeAt(cur))) cur++;
    const quote = t[cur]; cur++;
    const end = t.indexOf(quote, cur);
    out.push({name, raw: t.slice(cur, end), quote}); cur = end + 1;
  }
}

// ---- writer ---------------------------------------------------------------------------------------------------

export class Writer {
  constructor() { this.out = []; this.inTag = false; this.empty = false; }
  close() { if (this.inTag) { this.out.push(this.empty ? '/>' : '>'); this.inTag = false; } }
  checkName(name, what) { for (let i = 0; i < name.length; i++) if (invalidName(name.charCodeAt(i))) throw new XmlError('invalid element ' + what); }
  start(prefix, name) { if (prefix != null) this.checkName(prefix, 'prefix'); this.checkName(name, 'name'); this.close(); this.out.push('<', prefix != null ? prefix + ':' + name : name); this.inTag = true; this.empty = false; }
  emptyTag(prefix, name) { this.checkName(name, 'name'); this.close(); this.out.push('<', prefix != null ? prefix + ':' + name : name); this.inTag = true; this.empty = true; }
  attribute(name, value) {
    if (!this.inTag) throw new XmlError('attributes are only allowed inside tags');
    for (let i = 0; i < name.length; i++) if (invalidAttr(name.charCodeAt(i))) throw new XmlError('invalid attribute name');
    if (name.includes('\0') || name.includes('"')) throw new XmlError('invalid attribute value');
    this.out.push(' ', name, '="', contentEscape(value), '"');
  }
  rawAttribute(a) { if (!this.inTag) throw new XmlError('attributes are only allowed inside tags'); this.out.push(' ', a.name, '=', a.quote, a.raw, a.quote); }
  end(prefix, name) { if (prefix != null) this.checkName(prefix, 'prefix'); this.checkName(name, 'name'); this.close(); this.out.push('</', prefix != null ? prefix + ':' + name : name, '>'); }
  text(s) { this.close(); this.out.push(contentEscape(s)); }
  cdata(s) { if (s.includes(']]>')) throw new XmlError('cdata content cannot contain `]]>`'); this.close(); this.out.push('<![CDATA[', s, ']]>'); }
  comment(s) { this.close(); this.out.push('<!--', commentEscape(s), '-->'); }
  raw(s) { this.close(); this.out.push(s); }
  finish() { this.close(); return this.out.join(''); }
}

// ---- tree (ftlman xmltree) -------------------------------------------------------------------------------------

/** Element: {prefix, name, attrs: Map (written sorted), children}. Other nodes: {text}|{cdata}|{comment}. */
export function createElement(ev) {
  const attrs = new Map();
  for (const a of attributes(ev.attrs)) attrs.set(a.name, unescape(a.raw));
  return {prefix: ev.prefix, name: ev.name, attrs, children: []};
}

function toNode(reader, ev) {
  switch (ev.type) {
    case 'start': { const el = createElement(ev); buildUnder(reader, el); return el; }
    case 'empty': return createElement(ev);
    case 'text': return {text: unescape(ev.raw)};
    case 'cdata': return {cdata: ev.content};
    case 'comment': return {comment: ev.content};
    default: return undefined;
  }
}

export function buildUnder(reader, element) {
  for (;;) {
    const ev = reader.next();
    if (ev === null || ev.type === 'end') return;
    const node = toNode(reader, ev);
    if (node !== undefined) element.children.push(node);
  }
}

/** parse_all_with_options: every top-level node (unmatched end tags and doctypes ignored). */
export function parseAll(text, options) {
  const reader = new Reader(text, options), out = [];
  for (;;) {
    const ev = reader.next();
    if (ev === null) return out;
    if (ev.type === 'end') continue;
    const node = toNode(reader, ev);
    if (node !== undefined) out.push(node);
  }
}

/** First element of the document, or null. */
export function parseElement(text, options) { return parseAll(text, options).find(n => n.name !== undefined) ?? null; }

const byName = (a, b) => (a < b ? -1 : a > b ? 1 : 0);
export function writeElement(w, el) {
  const empty = el.children.length === 0;
  if (empty) w.emptyTag(el.prefix, el.name); else w.start(el.prefix, el.name);
  for (const key of [...el.attrs.keys()].sort(byName)) w.attribute(key, el.attrs.get(key));
  if (!empty) { writeChildren(w, el); w.end(el.prefix, el.name); }
}
export function writeChildren(w, el) {
  for (const n of el.children) {
    if (n.name !== undefined) writeElement(w, n);
    else if (n.text !== undefined) w.text(n.text);
    else if (n.cdata !== undefined) w.cdata(n.cdata);
    else w.comment(n.comment);
  }
}

export function cloneNode(n) {
  if (n.name === undefined) return {...n};
  return {prefix: n.prefix, name: n.name, attrs: new Map(n.attrs), children: n.children.map(cloneNode)};
}

/** Element::get_text_trim: Text children only (not CDATA), Rust whitespace trimming. */
export function textTrim(el) {
  let result = '';
  for (const n of el.children) if (n.text !== undefined) result += result === '' ? trimStart(n.text) : n.text;
  return trimEnd(result);
}
// Rust char::is_whitespace (Unicode White_Space): unlike JS trim, U+0085 counts and U+FEFF does not.
const RUST_WS = '\\t\\n\\v\\f\\r \\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000';
const START_WS = new RegExp('^[' + RUST_WS + ']+'), END_WS = new RegExp('[' + RUST_WS + ']+$');
export const trimStart = s => s.replace(START_WS, ''), trimEnd = s => s.replace(END_WS, '');
