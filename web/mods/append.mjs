// Slipstream-style XML append scripts as ftlman 0.7.4 parses and applies them (src/append/{parse,mod}.rs).
// Script: [{find}|{content: node}|{error}]. Errors inside scripts are kept as Error nodes and fail only when executed,
// exactly as ftlman does (it parses everything, then stops at the first Error it reaches).
import {Reader, XmlError, attributes, buildUnder, cloneNode, createElement, textTrim, trimEnd, trimStart, unescape} from './xml.mjs';

const REMOVE = '_FTLMAN_INTERNAL_REMOVE_MARKER', MOD_NS = new Set(['mod', 'mod-append', 'mod-prepend', 'mod-overwrite']);
const FIND_NAMES = new Set(['findName', 'findLike', 'findWithChildLike', 'findComposite']);
class Reported extends Error {}
export class PatchError extends Error {}

// ---- string filters --------------------------------------------------------------------------------------------

/** Rust `regex` pattern as a JS RegExp (subset: leading (?flags), Unicode classes); throws on invalid syntax. */
export function rustRegex(pattern) {
  let flags = 'u', p = pattern;
  const m = /^\(\?([imsx]+)\)/.exec(p);
  if (m) { if (m[1].includes('x')) throw new SyntaxError('verbose regex unsupported'); flags += m[1]; p = p.slice(m[0].length); }
  // Rust's \d, \w, \s are Unicode-aware; JS's are ASCII even with the u flag.
  p = p.replace(/\\\\|\\d|\\D|\\w|\\W|\\s|\\S/g, t => ({'\\d': '\\p{Nd}', '\\D': '\\P{Nd}', '\\w': '[\\p{Alphabetic}\\p{M}\\p{Nd}\\p{Pc}\\p{Join_Control}]', '\\W': '[^\\p{Alphabetic}\\p{M}\\p{Nd}\\p{Pc}\\p{Join_Control}]', '\\s': '\\p{White_Space}', '\\S': '\\P{White_Space}'})[t] ?? t);
  return new RegExp(p, [...new Set(flags)].join(''));
}
function stringFilter(value, regex) {
  if (!regex) return {fixed: value};
  return {re: rustRegex(value)};
}
// regex.find(value) must be the leftmost-first match and cover the whole value.
function matches(filter, value) {
  if (filter.fixed !== undefined) return value === filter.fixed;
  const m = filter.re.exec(value);
  return !!m && m.index === 0 && m[0].length === value.length;
}

// ---- parser ----------------------------------------------------------------------------------------------------

class Parser {
  constructor(text) { this.r = new Reader(text, {topLevelText: true, unmatchedClosing: true, unclosed: true}); }
  skipToEnd() { let depth = 1; while (depth > 0) { const e = this.r.next(); if (e === null) break; if (e.type === 'start') depth++; else if (e.type === 'end') depth--; } }
  skipIfNonEmpty(ev) { if (ev.type === 'start') this.skipToEnd(); }
  attr(ev, name) { let v; for (const a of attributes(ev.attrs)) if (a.name === name) v = a; return v ? unescape(v.raw) : undefined; }
  // parser_get_attr!(.., type, default): a present but unparsable value is an error (Reported), surfaced lazily.
  typed(ev, name, parse, dflt) { const v = this.attr(ev, name); if (v === undefined) return {ok: dflt}; const r = parse(v); return r === undefined ? {err: true} : {ok: r}; }
  textTrim() {
    let depth = 1, result = '';
    for (;;) {
      const e = this.r.next();
      if (e === null) break;
      if (e.type === 'start') depth++;
      else if (e.type === 'end') { if (depth === 1) break; depth--; }
      else if (e.type === 'text' && depth === 1) { const c = unescape(e.raw); result += result === '' ? trimStart(c) : c; }
    }
    return trimEnd(result);
  }
  element(ev) { const el = createElement(ev); if (ev.type === 'start') buildUnder(this.r, el); return el; }

  selector(ev, regex) {
    const attrs = []; let ok = true;
    for (const a of attributes(ev.attrs)) { try { attrs.push([a.name, stringFilter(unescape(a.raw), regex)]); } catch { ok = false; } }
    let value = null;
    if (ev.type === 'start') {
      const text = this.textTrim();
      if (text !== '') { try { value = stringFilter(text, regex); } catch { return {error: true}; } }
    }
    return ok ? {name: null, attrs, value} : {error: true};
  }

  par(ev) {
    const op = this.attr(ev, 'op'), operation = {AND: {complement: false, and: true}, OR: {complement: false, and: false}, NOR: {complement: true, and: false}, NAND: {complement: true, and: true}}[op];
    if (!operation) { this.skipIfNonEmpty(ev); return {error: true}; }
    const filters = [];
    for (;;) {
      const e = this.r.next();
      if (e === null || e.type === 'end') break;
      if (e.type !== 'start' && e.type !== 'empty') continue;
      if (e.prefix === 'mod' && e.name === 'par') {
        const inner = this.par(e);
        if (inner.error) { this.skipIfNonEmpty(e); return {error: true}; }
        filters.push({reverse: false, start: 0, limit: Infinity, panic: false, filter: {composite: inner}, commands: []});
      } else {
        const f = this.tryFind(e);
        if (f === 'unrecognized') { this.skipIfNonEmpty(e); return {error: true}; }
        if (f === 'reported') return {error: true};
        filters.push(f);
      }
    }
    return {operation, filters};
  }

  insertByFind(ev) {
    const addAnyway = this.typed(ev, 'addAnyway', parseBool, true);
    let unknown = false, find = null; const before = [], after = [];
    for (;;) {
      const e = this.r.next();
      if (e === null || e.type === 'end') break;
      if (e.type !== 'start' && e.type !== 'empty') continue;
      const f = this.tryFind(e);
      if (f === 'reported') throw new Reported();
      if (f !== 'unrecognized') { find = f; continue; }
      if (e.prefix === 'mod-before' || e.prefix === 'mod-after') { const el = this.element(e); el.prefix = null; (e.prefix === 'mod-before' ? before : after).push(el); continue; }
      unknown = true; this.skipIfNonEmpty(e);
    }
    if (!find || (!before.length && !after.length) || unknown) return {error: true};
    if (addAnyway.err) throw new Reported();
    return {insertByFind: {find, addAnyway: addAnyway.ok, before, after}};
  }

  // parse_commands; slots collect the first mod:selector / mod:par of the enclosing find.
  commands(slots) {
    const out = [];
    for (;;) {
      const e = this.r.next();
      if (e === null || e.type === 'end') break;
      if (e.type !== 'start' && e.type !== 'empty') continue;
      if (e.prefix === 'mod') {
        const f = this.tryFind(e);
        if (f === 'reported') { out.push({error: true}); continue; }
        if (f !== 'unrecognized') { out.push({find: f}); continue; }
        switch (e.name) {
          case 'selector':
            if (slots?.selector !== undefined && slots.selector === null) slots.selector = this.selector(e, slots.regex); else this.skipIfNonEmpty(e);
            continue;
          case 'par':
            if (slots?.par !== undefined && slots.par === null) slots.par = this.par(e); else this.skipIfNonEmpty(e);
            continue;
          case 'setAttributes': this.skipIfNonEmpty(e); out.push({setAttributes: attributes(e.attrs).map(a => [a.name, unescape(a.raw)])}); continue;
          case 'removeAttributes': this.skipIfNonEmpty(e); out.push({removeAttributes: attributes(e.attrs).map(a => a.name)}); continue;
          case 'setValue': out.push({setValue: e.type === 'start' ? this.textTrim() : ''}); continue;
          case 'removeTag': this.skipIfNonEmpty(e); out.push({removeTag: true}); continue;
          case 'insertByFind': {
            let cmd;
            try { cmd = this.insertByFind(e); } catch (x) { if (!(x instanceof Reported)) throw x; this.skipIfNonEmpty(e); cmd = {error: true}; }
            out.push(cmd); continue;
          }
          default: this.skipIfNonEmpty(e); out.push({error: true}); continue;
        }
      }
      if (e.prefix === 'mod-prepend' || e.prefix === 'mod-append' || e.prefix === 'mod-overwrite') {
        const el = this.element(e); el.prefix = null;
        out.push(e.prefix === 'mod-prepend' ? {prepend: el} : e.prefix === 'mod-append' ? {append: el} : {overwrite: el});
        continue;
      }
      this.skipIfNonEmpty(e); out.push({error: true});
    }
    return out;
  }

  // try_parse_find: a Find, 'unrecognized' (not a find tag) or 'reported' (invalid find).
  tryFind(ev) {
    if (ev.prefix !== 'mod' || !FIND_NAMES.has(ev.name)) return 'unrecognized';
    const kind = ev.name;
    const reverse = this.typed(ev, 'reverse', parseBool, kind === 'findName');
    const start = this.typed(ev, 'start', parseUsize, 0);
    const limitRaw = this.typed(ev, 'limit', parseIsize, kind === 'findName' ? 1 : -1);
    const limit = limitRaw.err ? {err: true} : limitRaw.ok >= 0 ? {ok: limitRaw.ok} : limitRaw.ok === -1 ? {ok: Infinity} : {err: true};
    const panicValue = this.attr(ev, 'panic'), panic = panicValue === undefined || panicValue === 'false' ? false : true;
    let commands = [], filter;
    const body = slots => { if (ev.type === 'start') { try { commands = this.commands(slots); } catch (x) { if (!(x instanceof Reported)) throw x; this.skipToEnd(); commands = [{error: true}]; } } };
    if (kind === 'findName') {
      const regex = this.typed(ev, 'regex', parseBool, false), asRegex = regex.ok ?? false;
      let name, type;
      try { const n = this.attr(ev, 'name'); name = n === undefined ? {err: true} : {ok: stringFilter(n, asRegex)}; } catch { name = {err: true}; }
      try { const t = this.attr(ev, 'type'); type = {ok: t === undefined ? null : stringFilter(t, asRegex)}; } catch { type = {err: true}; }
      body(null);
      if (regex.err || type.err || name.err) return 'reported';
      filter = {selector: {name: type.ok, attrs: [['name', name.ok]], value: null}};
    } else if (kind === 'findLike' || kind === 'findWithChildLike') {
      const regex = this.typed(ev, 'regex', parseBool, false), asRegex = regex.ok ?? false;
      let type, childType = {ok: null};
      try { const t = this.attr(ev, 'type'); type = {ok: t === undefined ? null : stringFilter(t, asRegex)}; } catch { type = {err: true}; }
      if (kind === 'findWithChildLike') { try { const t = this.attr(ev, 'child-type'); childType = {ok: t === undefined ? null : stringFilter(t, asRegex)}; } catch { childType = {err: true}; } }
      // findLike evaluates `attr_regex?` before reading its body: an invalid regex attribute leaves the body unread.
      if (kind === 'findLike' && regex.err) return 'reported';
      const slots = {selector: null, regex: asRegex};
      body(slots);
      if (slots.selector?.error) return 'reported';
      const selector = slots.selector ?? {name: null, attrs: [], value: null};
      if (kind === 'findLike') {
        if (type.err) return 'reported';
        filter = {selector: {...selector, name: type.ok}};
      } else {
        if (type.err || childType.err) return 'reported';
        filter = {withChild: {name: type.ok, child: {...selector, name: childType.ok}}};
      }
    } else {
      const slots = {par: null};
      body(slots);
      if (!slots.par || slots.par.error) return 'reported';
      filter = {composite: slots.par};
    }
    if (reverse.err || start.err || limit.err) return 'reported';
    return {reverse: reverse.ok, start: start.ok, limit: limit.ok, panic, filter, commands};
  }
}

const parseBool = v => v === 'true' ? true : v === 'false' ? false : undefined;
// Rust integer FromStr: optional '+' (and '-' for signed), ASCII digits only.
const parseUsize = v => /^\+?[0-9]+$/.test(v) && Number(v) <= Number.MAX_SAFE_INTEGER ? Number(v) : undefined;
const parseIsize = v => /^[+-]?[0-9]+$/.test(v) && Math.abs(Number(v)) <= Number.MAX_SAFE_INTEGER ? Number(v) : undefined;

/** append::parse: the script nodes of an append document (already unwrapped). XML errors throw XmlError. */
export function parseScript(text) {
  const p = new Parser(text), script = [];
  for (;;) {
    const e = p.r.next();
    if (e === null) return script;
    if (e.type === 'end' || e.type === 'doctype') continue;
    if ((e.type === 'start' || e.type === 'empty') && e.prefix === 'mod') {
      const f = p.tryFind(e);
      if (f === 'unrecognized') { p.skipIfNonEmpty(e); script.push({error: true}); }
      else if (f === 'reported') script.push({error: true});
      else script.push({find: f});
      continue;
    }
    if (e.type === 'start' || e.type === 'empty') script.push({content: p.element(e)});
    else if (e.type === 'text') script.push({content: {text: unescape(e.raw)}});
    else if (e.type === 'cdata') script.push({content: {cdata: e.content}});
    else if (e.type === 'comment') script.push({content: {comment: e.content}});
  }
}

// ---- patching --------------------------------------------------------------------------------------------------

function selectorMatches(f, el) {
  if (f.name && !matches(f.name, el.name)) return false;
  for (const [key, value] of f.attrs) { const v = el.attrs.get(key); if (v === undefined || !matches(value, v)) return false; }
  if (f.value && !matches(f.value, textTrim(el))) return false;
  return true;
}
function filterMatches(filter, el) {
  if (filter.selector) return selectorMatches(filter.selector, el);
  if (filter.name && !matches(filter.name, el.name)) return false;
  return el.children.some(c => c.name !== undefined && selectorMatches(filter.child, c));
}

function findRaw(elements, find) {
  let result;
  if (find.filter.composite) {
    const {operation, filters} = find.filter.composite;
    // An empty mod:par returns before reverse/start/limit/panic are applied.
    if (!filters.length) return operation.complement ? elements.slice() : [];
    {
      let set = new Set(findRaw(elements, filters[0]));
      for (const child of filters.slice(1)) {
        const r = findRaw(elements, child);
        if (operation.and) { const c = new Set(r); set = new Set([...set].filter(x => c.has(x))); } else for (const x of r) set.add(x);
      }
      result = operation.complement ? elements.filter(e => !set.has(e)) : elements.filter(e => set.has(e));
    }
  } else {
    const f = find.filter.selector ? {selector: find.filter.selector} : find.filter.withChild;
    result = elements.filter(e => filterMatches(f, e));
  }
  if (find.reverse) result.reverse();
  // result.truncate(start + limit) with usize wrap-around: limit -1 is usize::MAX, so start > 0 truncates to start - 1.
  const end = find.limit === Infinity ? (find.start === 0 ? Infinity : find.start - 1) : find.start + find.limit;
  if (result.length > end) result.length = end;
  result.splice(0, Math.min(find.start, result.length));
  if (find.panic && result.length === 0) throw new PatchError('find tag panicked: it did not match any elements');
  return result;
}
const findIn = (context, find) => findRaw(context.children.filter(n => n.name !== undefined && n.prefix !== REMOVE), find);

function runCommands(context, commands) {
  for (const c of commands) {
    if (c.find) { for (const m of findIn(context, c.find)) runCommands(m, c.find.commands); }
    else if (c.setAttributes) { for (const [k, v] of c.setAttributes) context.attrs.set(k, v); }
    else if (c.removeAttributes) { for (const k of c.removeAttributes) context.attrs.delete(k); }
    else if (c.setValue !== undefined) { context.children = [{text: c.setValue}]; }
    else if (c.removeTag) context.prefix = REMOVE;
    else if (c.prepend) { const n = cloneNode(c.prepend); n.prefix = null; context.children.unshift(n); }
    else if (c.append) { const n = cloneNode(c.append); n.prefix = null; context.children.push(n); }
    else if (c.overwrite) {
      const n = cloneNode(c.overwrite); n.prefix = null;
      const i = context.children.findIndex(x => x.name !== undefined && x.name === n.name);
      if (i >= 0) context.children[i] = n; else context.children.push(n);
    } else if (c.insertByFind) {
      const {find, addAnyway, before, after} = c.insertByFind, results = findIn(context, find);
      const b = before.map(cloneNode), a = after.map(cloneNode);
      if (!results.length) { if (addAnyway) { context.children.unshift(...b); context.children.push(...a); } }
      else {
        const first = context.children.indexOf(results[0]), last = context.children.indexOf(results[results.length - 1]);
        context.children.splice(first, 0, ...b);
        context.children.splice(last + b.length + 1, 0, ...a);
      }
    } else throw new PatchError('invalid mod command reached');
  }
}

function cleanup(el) {
  if (el.prefix !== null && MOD_NS.has(el.prefix)) el.prefix = null;
  const kept = [];
  for (const c of el.children) {
    if (c.name !== undefined) { if (c.prefix === REMOVE) continue; cleanup(c); kept.push(c); }
    else if (c.comment === undefined) kept.push(c);
  }
  el.children = kept;
}

/** append::patch on the root element. */
export function patch(root, script) {
  for (const node of script) {
    if (node.find) { for (const m of findIn(root, node.find)) runCommands(m, node.find.commands); }
    else if (node.content) { const n = cloneNode(node.content); if (n.name !== undefined) cleanup(n); root.children.push(n); }
    else throw new PatchError('invalid append script node reached');
  }
  cleanup(root);
}

export {XmlError};
