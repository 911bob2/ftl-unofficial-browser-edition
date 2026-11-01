#!/usr/bin/env node
// Hyperspace Lua API generator. Reads the C++ wrappers SWIG 4.0.1 generates from Hyperspace's own .i files
// (tools/hs-bindgen/swig.sh produces them from the pinned HS source) and the HS/FTL headers for enum values,
// and writes the tables our bridge uses:
//   hs-lua/gen/hs_api.c        classes, attributes, methods with SWIG's argument checks, constants, namespaces
//   engine/.../hs/lua/LuaApi.kt type ids, attribute codes and method ids for the Kotlin adapters
//   <out>/catalog.json         everything parsed (debugging aid, not committed)
// Only names, kinds and enum values cross over; no HS code is copied. Usage:
//   node tools/hs-bindgen/bindgen.mjs <swig-out-dir> <hs-src-dir> <repo-root>
import fs from 'node:fs';
import path from 'node:path';

const [swigDir, hsDir, repo] = process.argv.slice(2);
if (!swigDir || !hsDir || !repo) { console.error('usage: bindgen.mjs <swig-out-dir> <hs-src-dir> <repo-root>'); process.exit(2); }
const MODULES = ['Hyperspace', 'Graphics', 'Defines', 'RapidXML'];
const FILES = {Hyperspace: 'hyperspace.cxx', Graphics: 'graphics.cxx', Defines: 'defines.cxx', RapidXML: 'rapidxml.cxx'};

// ---------------------------------------------------------------- enum values from the headers
const enums = new Map(); // qualified name -> value; also unqualified
function headerFiles() {
  const out = [];
  const walk = d => { for (const e of fs.readdirSync(d, {withFileTypes: true})) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p); else if (/\.(h|hpp)$/.test(e.name)) out.push(p);
  } };
  walk(path.join(hsDir, 'src')); walk(path.join(hsDir, 'lua')); walk(path.join(hsDir, 'third_party', 'rapidxml'));
  out.unshift(path.join(swigDir, '..', 'FTLGameELF64.h'));
  return out;
}
function stripComments(s) { return s.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\n]*/g, ' '); }
const pendingExpr = [];
function defineEnum(scopes, names) {
  let next = 0;
  for (const raw of names) {
    const item = raw.trim(); if (!item) continue;
    const m = item.match(/^([A-Za-z_]\w*)\s*(?:=\s*([\s\S]+))?$/); if (!m) continue;
    const name = m[1];
    let val;
    if (m[2] !== undefined) { val = evalExpr(m[2].trim(), scopes); if (val === undefined) { pendingExpr.push({scopes, name, expr: m[2].trim()}); val = NaN; } }
    else val = next;
    for (const sc of scopes) enums.set(sc ? sc + '::' + name : name, val);
    next = (Number.isNaN(val) ? NaN : val + 1);
  }
}
function evalExpr(expr, scopes) {
  expr = expr.replace(/\b(\d+)[uUlL]+\b/g, '$1');
  if (/^-?(0x[0-9a-fA-F]+|\d+)$/.test(expr)) return Number(expr);
  if (/^\(?-?\s*\d+\s*\)?$/.test(expr)) return Number(expr.replace(/[()\s]/g, ''));
  const id = expr.replace(/^\(int\)\s*/, '');
  for (const sc of scopes.concat([''])) { const k = sc ? sc + '::' + id : id; if (enums.has(k) && !Number.isNaN(enums.get(k))) return enums.get(k); }
  if (enums.has(id) && !Number.isNaN(enums.get(id))) return enums.get(id);
  // simple binary expressions: A | B, A + 1, 1 << 3
  const bin = id.replace(/^\((.*)\)$/, '$1').match(/^(.+?)\s*([|+\-*]|<<)\s*(.+)$/);
  if (bin) { const a = evalExpr(bin[1].trim(), scopes), b = evalExpr(bin[3].trim(), scopes);
    if (a !== undefined && b !== undefined) return bin[2] === '|' ? (a | b) : bin[2] === '+' ? a + b : bin[2] === '-' ? a - b : bin[2] === '*' ? a * b : a << b; }
  return undefined;
}
function parseEnums(text) {
  text = stripComments(text);
  // track struct/class/namespace scopes approximately with brace depth
  const tokens = text.split(/(\{|\})/);
  const stack = []; let pendingScope = null;
  for (let i = 0; i < tokens.length; i++) {
    const t = tokens[i];
    if (t === '{') { stack.push(pendingScope); pendingScope = null; continue; }
    if (t === '}') { stack.pop(); continue; }
    // NAMED_ENUM(Name, A, B, ...)
    for (const m of t.matchAll(/NAMED_ENUM\(\s*(\w+)\s*,([\s\S]*?)\)/g)) {
      const scope = stack.filter(Boolean).join('::');
      defineEnum([scope, scope ? scope + '::' + m[1] : m[1]], m[2].split(','));
    }
    const em = t.match(/\benum\s+(?:class\s+|struct\s+)?(\w+)?\s*(?::\s*[\w\s]+)?\s*$/);
    if (em && tokens[i + 1] === '{') {
      const body = tokens[i + 2] || '';
      const scope = stack.filter(Boolean).join('::');
      const isClass = /\benum\s+(class|struct)\b/.test(t);
      const name = em[1];
      const scopes = isClass ? [scope ? scope + '::' + name : name] : [scope, name ? (scope ? scope + '::' + name : name) : null].filter(x => x !== null);
      defineEnum(scopes.length ? scopes : [''], body.split(','));
      i += 3; // skip body and closing brace
      continue;
    }
    const sm = t.match(/\b(struct|class|namespace)\s+(?:LIBZHL_INTERFACE\s+)?(\w+)[^;{}()]*$/);
    pendingScope = sm ? sm[2] : null;
  }
}
for (const f of headerFiles()) { try { parseEnums(fs.readFileSync(f, 'utf8')); } catch (e) { /* unreadable header */ } }
for (let pass = 0; pass < 4; pass++) for (const p of pendingExpr) {
  const v = evalExpr(p.expr, p.scopes); if (v !== undefined) for (const sc of p.scopes) enums.set(sc ? sc + '::' + p.name : p.name, v);
}

// ---------------------------------------------------------------- SWIG wrapper parsing
function cstr(lit) { // concatenated C string literals -> JS string
  let out = ''; for (const m of lit.matchAll(/"((?:[^"\\]|\\.)*)"/g)) out += JSON.parse('"' + m[1].replace(/\\'/g, "'").replace(/\\([0-7]{1,3})/g, (_, o) => '\\u' + parseInt(o, 8).toString(16).padStart(4, '0')) + '"');
  return out;
}
function parseModule(mod) {
  const src = fs.readFileSync(path.join(swigDir, FILES[mod]), 'utf8');
  const wrappers = new Map();
  for (const m of src.matchAll(/^static int (_wrap_\w+)\(lua_State\* L\) \{\n([\s\S]*?)\n\}\n/gm)) wrappers.set(m[1], m[2]);
  const swigTypes = new Map(); // mangled -> {str, cls}
  for (const m of src.matchAll(/^static swig_type_info _swigt__p_(\w+) = \{"_p_\w+", "([^"]*)", 0, 0, \(void\*\)(?:&_wrap_class_(\w+)|0), 0\};/gm))
    swigTypes.set(m[1], {str: m[2], cls: m[3] || null});
  const arr = (name, kind) => {
    const m = src.match(new RegExp('^static swig_lua_' + kind + '\\*? ' + name.replace(/[[\]]/g, '\\$&') + '\\[\\] ?= ?\\{\\n([\\s\\S]*?)\\n\\};', 'm'));
    return m ? m[1] : '';
  };
  const attrs = body => [...body.matchAll(/\{ "(\w+)", (\w+), (\w+) \}/g)].map(m => ({name: m[1], get: m[2], set: m[3]}));
  const methods = body => [...body.matchAll(/\{ "([\w]+)", (\w+)\}/g)].map(m => ({name: m[1], fn: m[2]}));
  const consts = body => [...body.matchAll(/\{SWIG_LUA_CONSTTAB_(\w+)\("(\w+)", ([^)]*?(?:\([^)]*\))?[^)]*)\)\}/g)].map(m => ({ctype: m[1], name: m[2], expr: m[3].trim()}));
  const classesOf = body => [...body.matchAll(/&_wrap_class_(\w+)/g)].map(m => m[1]);
  const classes = [];
  for (const m of src.matchAll(/^static swig_lua_class _wrap_class_(\w+) = \{ "(\w+)", "([\w:]+)", &SWIGTYPE_p_(\w+),\s*(\w+),\s*(\w+), swig_(\w+)_methods, swig_\w+_attributes, &(swig_\w+_SwigStatic), swig_\w+_meta, swig_\w+_bases, swig_\w+_base_names \};/gm)) {
    const [, id, name, fqname, mangled, ctor, dtor, P, SP] = m;
    const baseNames = [...(arr('swig_' + P + '_base_names', 'char') || '').matchAll(/"([^"]*)"/g)].map(x => x[1]);
    const bn = src.match(new RegExp('^static const char \\*swig_' + P + '_base_names\\[\\] = \\{([^}]*)\\};', 'm'));
    const bases = bn ? [...bn[1].matchAll(/"([^"]*)"/g)].map(x => x[1]) : baseNames;
    classes.push({
      id, name, fqname, mangled, ctor: ctor === '0' ? null : ctor.replace(/^_proxy_/, ''), hasDtor: dtor !== '0', bases,
      attrs: attrs(arr('swig_' + P + '_attributes', 'attribute')),
      methods: methods(arr('swig_' + P + '_methods', 'method')),
      meta: methods(arr('swig_' + P + '_meta', 'method')),
      sattrs: attrs(arr(SP + '_attributes', 'attribute')),
      sconsts: consts(arr(SP + '_constants', 'const_info')),
      smethods: methods(arr(SP + '_methods', 'method')),
      sclasses: classesOf(arr(SP + '_classes', 'class')),
    });
  }
  const module = {
    attrs: attrs(arr('swig_SwigModule_attributes', 'attribute')),
    consts: consts(arr('swig_SwigModule_constants', 'const_info')),
    methods: methods(arr('swig_SwigModule_methods', 'method')),
    classes: classesOf(arr('swig_SwigModule_classes', 'class')),
  };
  const lc = src.match(/^const char\* SWIG_LUACODE=\n([\s\S]*?);\n/m);
  const luacode = lc ? cstr(lc[1]) : '';
  // the %init additions that are not data: native ".get" members (the per-object table) and CFPS.speedEnabled
  const native = [...src.matchAll(/script_add_native_member\(L, "(\w+)", "(\w+)", (\w+)\);/g)].map(x => ({cls: x[1], member: x[2], fn: x[3]}));
  // hyperspace.i's %init adds CFPS.speedEnabled to the class's .get/.set tables by hand (lambdas around HS's global
  // `speedEnabled`): a bool member whose setter checks only the value (lua_isboolean, "CFPS::speedEnabled" arg 2).
  if (/SWIG_Lua_get_class_metatable\(L, "CFPS"\);[\s\S]{0,200}SWIG_Lua_add_function\(L, "speedEnabled"/.test(src)) {
    const cfps = classes.find(c => c.name === 'CFPS');
    if (cfps) {
      wrappers.set('_hs_CFPS_speedEnabled_get', '  lua_pushboolean(L, speedEnabled);');
      wrappers.set('_hs_CFPS_speedEnabled_set', '  SWIG_check_num_args("CFPS::speedEnabled",2,2)\n  if(!lua_isboolean(L,2)) SWIG_fail_arg("CFPS::speedEnabled",2,"bool");');
      cfps.attrs.push({name: 'speedEnabled', get: '_hs_CFPS_speedEnabled_get', set: '_hs_CFPS_speedEnabled_set'});
    }
  }
  return {mod, wrappers, swigTypes, classes, module, luacode, native, src};
}

// arg checks of a normal wrapper -> [{i, check, type}] plus name strings
const CHECKS = {SWIG_isptrtype: 'ptr', lua_isuserdata: 'ud', lua_isnumber: 'num', lua_isboolean: 'bool', SWIG_lua_isnilstring: 'nstr', lua_isstring: 'str', lua_isfunction: 'fn', lua_istable: 'tab'};
function analyse(body, mods) {
  if (!body) return null;
  if (/argc = lua_gettop\(L\);/.test(body)) {
    // overload dispatcher
    const cands = [];
    const re = /if \(argc == (\d+)\) \{([\s\S]*?)\n  \}\n/g; let m;
    while ((m = re.exec(body))) {
      const argc = +m[1]; const blk = m[2];
      // each candidate: sequence of checks then return _wrap_X(L);
      const parts = blk.split(/return (_wrap_\w+)\(L\);/);
      for (let k = 0; k + 1 < parts.length; k += 2) {
        const chk = parts[k];
        const checks = [];
        for (const c of chk.matchAll(/(?:_v = (lua_\w+|SWIG_lua_isnilstring)\(L,argv\[(\d+)\]\))|(?:if \((lua_isuserdata|SWIG_isptrtype)\(L,argv\[(\d+)\]\)==0 \|\| SWIG_ConvertPtr\(L,argv\[\d+\], \(void \*\*\) &ptr, SWIGTYPE_p_(\w+), (\w+)\)\))/g)) {
          if (c[1]) checks.push({i: +c[2] + 1, check: CHECKS[c[1]] || c[1]});
          else checks.push({i: +c[4] + 1, check: c[3] === 'lua_isuserdata' ? 'udT' : 'ptrT', type: c[5], nonull: c[6] !== '0'});
        }
        cands.push({argc, checks, target: parts[k + 1]});
      }
    }
    const em = body.match(/SWIG_Lua_pusherrstring\(L,((?:\s*"(?:[^"\\]|\\.)*")+)\);/);
    return {dispatch: true, cands, error: em ? cstr(em[1]) : 'Wrong arguments for overloaded function'};
  }
  const nm = body.match(/SWIG_check_num_args\("([^"]*)",(\d+),(\d+)\)/);
  const args = [];
  for (const c of body.matchAll(/if\(!(\w+)\(L,(\d+)\)\) SWIG_fail_arg\("[^"]*",\d+,"([^"]*)"\);/g)) args.push({i: +c[2], check: CHECKS[c[1]] || c[1], ctype: c[3]});
  for (const c of body.matchAll(/SWIG_ConvertPtr\(L,(\d+),\(void\*\*\)&arg\d+,SWIGTYPE_p_(\w+),(\w+)\)\)\)\{\n\s*SWIG_fail_ptr\("(\w+)"/g)) {
    const a = args.find(x => x.i === +c[1]); if (a) { a.type = c[2]; a.ptrFlags = c[3]; a.failName = c[4]; }
  }
  for (const c of body.matchAll(/if \(lua_tonumber\(L, (\d+)\) < 0\) SWIG_exception/g)) { const a = args.find(x => x.i === +c[1]); if (a) a.unsigned = true; }
  const failName = (body.match(/SWIG_fail_ptr\("(\w+)"/) || [])[1] || null;
  // result kinds (informative; Kotlin pushes the values)
  const pushes = [];
  for (const p of body.matchAll(/(lua_pushnumber|lua_pushboolean|lua_pushlstring|lua_pushstring|SWIG_NewPointerObj)\(L,\s*(?:\(void \*\) )?(?:[^,]*?),?\s*(?:SWIGTYPE_p_(\w+)),?\s*(\d)?/g)) pushes.push(p[2] ? {k: 'obj', type: p[2], own: p[3] === '1'} : {k: p[1]});
  for (const p of body.matchAll(/(lua_pushnumber|lua_pushboolean|lua_pushlstring|lua_pushstring)\(L/g)) pushes.push({k: p[1].replace('lua_push', '')});
  let getKind = null;
  if (/lua_pushnumber\(L, \(lua_Number\)/.test(body)) getKind = 'num';
  else if (/lua_pushboolean\(L,/.test(body)) getKind = 'bool';
  else if (/lua_pushlstring\(L,|lua_pushstring\(L,/.test(body)) getKind = 'str';
  else { const dyn = body.match(/SWIG_TypeDynamicCast\(SWIGTYPE_p_(\w+), \(void \*\*\) &result\);\s*SWIG_NewPointerObj\(L,\(void\*\)result,ty,(\d)\)/);
    // array members (HS's *_Array_Size_N_Wrapper typemap): SWIG_NewPointerObj(L, &arg1->member, SWIGTYPE_p_X, 0)
    const arr = body.match(/SWIG_NewPointerObj\(L, &arg1->\w+, SWIGTYPE_p_(\w+), (\d)\)/);
    const o = dyn ? [null, 'result', dyn[1], dyn[2]] : arr ? [null, 'arg1', arr[1], arr[2]] : body.match(/SWIG_NewPointerObj\(L,(?:\(void \*\) )?(\w+),SWIGTYPE_p_(\w+),(\d)\)/); if (o) {
    const member = new RegExp('result = \\([^)]*\\)\\s*& ?\\(\\(arg1\\)->').test(body) || /SWIG_NewPointerObj\(L, &arg1->/.test(body);
    getKind = o[3] === '1' ? 'val' : member ? 'mem' : 'ptr'; pushes.typeOf = o[2]; } }
  const call = (body.match(/\n  (?:result = [^;]*?)?\(?(?:\(arg1\)->|)([A-Za-z_][\w:]*(?:<[^>]*>)?(?:::\w+)?)\(/) || [])[1];
  return {dispatch: false, name: nm ? nm[1] : null, nmin: nm ? +nm[2] : 0, nmax: nm ? +nm[3] : 0, args, failName, getKind, objType: pushes.typeOf || null,
    contract: /SWIG_contract_assert/.test(body), throws: /catch\(std::out_of_range/.test(body), vecGet: /____getitem__\(arg1,arg2\)/.test(body)};
}

const parsed = MODULES.map(parseModule);
// global type table: mangled swig type -> id. Classes first (by module order), then other swig types used as args.
const types = []; const typeId = new Map();
function addType(mangled, info) { if (typeId.has(mangled)) return typeId.get(mangled); const id = types.length + 1; typeId.set(mangled, id); types.push({id, mangled, ...info}); return id; }
for (const pm of parsed) for (const c of pm.classes) addType(c.mangled, {name: c.name, fqname: c.fqname, mod: pm.mod, str: (pm.swigTypes.get(c.mangled) || {}).str || c.fqname + ' *', cls: c});
for (const pm of parsed) for (const [mangled, t] of pm.swigTypes) if (!typeId.has(mangled)) addType(mangled, {name: null, fqname: null, mod: pm.mod, str: t.str, cls: null});
// bases by name (fqname "Base *" strings in base_names)
const byFq = new Map(); for (const t of types) if (t.cls) byFq.set(t.cls.fqname, t);
for (const t of types) if (t.cls) t.baseIds = t.cls.bases.map(b => { const n = b.replace(/\s*\*\s*$/, '').trim(); const bt = byFq.get(n) || types.find(x => x.name === n) || types.find(x => x.str === b.trim()); return bt ? bt.id : 0; }).filter(Boolean);
// SWIG resolves base names at run time by the type string (SWIG_TypeQuery); report any that stay unresolved
for (const t of types) if (t.cls && t.baseIds.length !== t.cls.bases.length) console.warn('unresolved base of ' + t.name + ': ' + t.cls.bases.join(', '));

// attributes and methods get global ids
const attrList = []; const methodList = []; const methodByFn = new Map();
function methodId(pm, fn, label) {
  const key = pm.mod + ':' + fn; if (methodByFn.has(key)) return methodByFn.get(key);
  const id = methodList.length + 1; methodByFn.set(key, id);
  const info = analyse(pm.wrappers.get(fn), pm) || {dispatch: false, name: label, nmin: 0, nmax: 99, args: []};
  const m = {id, fn, label, mod: pm.mod, ...info};
  methodList.push(m);
  if (info.dispatch) m.cands = info.cands.map(c => ({...c, target: methodId(pm, c.target, label), checks: c.checks.map(k => ({...k, typeId: k.type ? typeId.get(k.type) || 0 : 0}))}));
  for (const a of m.args || []) if (a.type) a.typeId = typeId.get(a.type) || 0;
  return id;
}
function attrEntry(pm, owner, a, isStatic) {
  const get = analyse(pm.wrappers.get(a.get), pm) || {};
  const setImmutable = a.set === 'SWIG_Lua_set_immutable' || a.set === '0';
  const set = setImmutable ? null : analyse(pm.wrappers.get(a.set), pm);
  const valArg = set ? (set.args || []).find(x => x.i === (isStatic ? 1 : 2)) : null;
  const id = attrList.length + 1;
  const e = {id, owner, name: a.name, isStatic, kind: get.getKind || 'num', objType: get.objType ? typeId.get(get.objType) || 0 : 0,
    set: set ? (valArg ? {check: valArg.check, ctype: valArg.ctype, unsigned: !!valArg.unsigned, typeId: valArg.type ? typeId.get(valArg.type) || 0 : 0, failName: valArg.failName || set.failName} : {check: 'any'}) : null,
    getName: get.name || (set && set.name), setName: set ? set.name : null};
  attrList.push(e); return id;
}
const constVal = (c, owner) => {
  if (c.ctype === 'STRING' || c.ctype === 'CHAR') return {s: cstr(c.expr)};
  let v = evalExpr(c.expr, [owner || '']);
  if (v === undefined) { const k = c.expr.replace(/^\(\w+\)/, ''); v = enums.get(k); }
  return {n: v === undefined || Number.isNaN(v) ? null : v, expr: c.expr};
};
const unresolved = [];
for (const pm of parsed) {
  for (const c of pm.classes) {
    const t = types[typeId.get(c.mangled) - 1];
    t.attrIds = c.attrs.map(a => attrEntry(pm, t.id, a, false));
    t.methods = c.methods.map(m => ({name: m.name, mid: methodId(pm, m.fn, c.name + '.' + m.name)}));
    t.metaM = c.meta.map(m => ({name: m.name, mid: methodId(pm, m.fn, c.name + '.' + m.name)}));
    t.sattrIds = c.sattrs.map(a => attrEntry(pm, t.id, a, true));
    t.smethods = c.smethods.map(m => ({name: m.name, mid: methodId(pm, m.fn, c.name + '.' + m.name)}));
    t.sconsts = c.sconsts.map(k => { const v = constVal(k, c.fqname); if (v.n === null && v.s === undefined) unresolved.push(c.name + '.' + k.name + ' = ' + k.expr); return {name: k.name, ...v}; });
    t.ctorMid = c.ctor ? methodId(pm, c.ctor, c.name + '()') : 0;
    t.sclasses = c.sclasses.map(id => { const cc = pm.classes.find(x => x.id === id); return cc ? typeId.get(cc.mangled) : 0; }).filter(Boolean);
    t.native = pm.native.filter(n => n.cls === c.name).map(n => n.member);
  }
  pm.ns = {
    attrIds: pm.module.attrs.map(a => attrEntry(pm, 0, a, true)),
    methods: pm.module.methods.map(m => ({name: m.name, mid: methodId(pm, m.fn, pm.mod + '.' + m.name)})),
    consts: pm.module.consts.map(k => { const v = constVal(k, ''); if (v.n === null && v.s === undefined) unresolved.push(pm.mod + '.' + k.name + ' = ' + k.expr); return {name: k.name, ...v}; }),
    classes: pm.module.classes.map(id => { const cc = pm.classes.find(x => x.id === id); return cc ? typeId.get(cc.mangled) : 0; }).filter(Boolean),
  };
}

// ---------------------------------------------------------------- emit C
const q = s => JSON.stringify(s);
const ident = s => s.replace(/[^A-Za-z0-9_]/g, '_');
const CHECK_CODE = {any: 0, num: 1, bool: 2, nstr: 3, str: 4, ptr: 5, ud: 6, fn: 7, tab: 8, ptrT: 5, udT: 6};
const KIND_CODE = {num: 1, bool: 2, str: 3, ptr: 4, mem: 5, val: 6};
let c = `/* Generated by tools/hs-bindgen/bindgen.mjs from SWIG 4.0.1's wrappers of FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07's .i files. Do not edit.
 * Names, member kinds, argument checks and enum values of Hyperspace's Lua API (Hyperspace is CC-BY-SA 4.0,
 * see third_party/notices/NOTICE.md). */
#include "../hs_api.h"
`;
// strings pool via direct literals
c += `const int hs_ntypes = ${types.length};\n`;
c += `const HsType hs_types[${types.length + 1}] = {\n  {0},\n`;
for (const t of types) {
  c += `  {${q(t.name || '')}, ${q(t.str)}, ${t.cls ? 1 : 0}},\n`;
}
c += '};\n';
// bases
c += `const short hs_bases[] = {0`;
let boff = 1; const baseOff = new Map();
for (const t of types) { if (t.cls && t.baseIds.length) { baseOff.set(t.id, boff); for (const b of t.baseIds) { c += ',' + b; boff++; } c += ',0'; boff++; } }
c += '};\n';
// attributes
c += `const int hs_nattrs = ${attrList.length};\nconst HsAttr hs_attrs[${attrList.length + 1}] = {\n  {0},\n`;
for (const a of attrList) {
  const s = a.set;
  c += `  {${q(a.name)}, ${KIND_CODE[a.kind] || 1}, ${a.objType}, ${s ? CHECK_CODE[s.check] ?? 0 : -1}, ${s && s.unsigned ? 1 : 0}, ${s ? s.typeId : 0}, ${q(a.getName || '')}, ${q((s && s.ctype) || '')}, ${q((s && s.failName) || '')}},\n`;
}
c += '};\n';
// methods
let argPool = []; let candPool = [];
c += `const int hs_nmethods = ${methodList.length};\n`;
const mrows = [];
for (const m of methodList) {
  if (m.dispatch) {
    const off = candPool.length;
    for (const cd of m.cands) { const aoff = argPool.length; for (const k of cd.checks) argPool.push({i: k.i, check: CHECK_CODE[k.check] ?? 0, typeId: k.typeId || 0, nonull: k.nonull ? 1 : 0, ctype: ''}); candPool.push({argc: cd.argc, aoff, n: cd.checks.length, target: cd.target}); }
    mrows.push(`  {${q(m.label)}, ${q(m.error)}, "", 1, ${off}, ${m.cands.length}, 0, 0, 0},`);
  } else {
    const aoff = argPool.length;
    for (const a of m.args || []) argPool.push({i: a.i, check: CHECK_CODE[a.check] ?? 0, typeId: a.typeId || 0, nonull: 0, unsigned: a.unsigned ? 1 : 0, ctype: a.ctype || '', failName: a.failName || ''});
    mrows.push(`  {${q(m.label)}, ${q(m.name || m.label)}, ${q(m.failName || '')}, 0, ${aoff}, ${(m.args || []).length}, ${m.nmin}, ${m.nmax}, ${m.vecGet ? 1 : 0}},`);
  }
}
c += `const HsMethod hs_methods[${methodList.length + 1}] = {\n  {0},\n${mrows.join('\n')}\n};\n`;
c += `const HsArg hs_args[${argPool.length + 1}] = {\n${argPool.map(a => `  {${a.i}, ${a.check}, ${a.typeId}, ${a.nonull}, ${a.unsigned || 0}, ${q(a.ctype)}, ${q(a.failName || '')}},`).join('\n')}\n  {0}\n};\n`;
c += `const HsCand hs_cands[${candPool.length + 1}] = {\n${candPool.map(k => `  {${k.argc}, ${k.aoff}, ${k.n}, ${k.target}},`).join('\n')}\n  {0}\n};\n`;
// per-type member lists (instance attrs, methods, meta, statics, consts)
const memRows = []; const constRows = [];
function members(list) { const off = memRows.length; for (const x of list) memRows.push(x); return {off, n: list.length}; }
function constsOf(list) { const off = constRows.length; for (const k of list) constRows.push(k); return {off, n: list.length}; }
c += `const HsClass hs_classes[${types.length + 1}] = {\n  {0},\n`;
for (const t of types) {
  if (!t.cls) { c += '  {0},\n'; continue; }
  const ia = members(t.attrIds.map(id => ({k: 'a', id, name: attrList[id - 1].name})));
  const im = members(t.methods.map(m => ({k: 'm', id: m.mid, name: m.name})));
  const mm = members(t.metaM.map(m => ({k: 'm', id: m.mid, name: m.name})));
  const sa = members(t.sattrIds.map(id => ({k: 'a', id, name: attrList[id - 1].name})));
  const sm = members(t.smethods.map(m => ({k: 'm', id: m.mid, name: m.name})));
  const sc = constsOf(t.sconsts);
  const nat = t.native.includes('table') ? 1 : 0;
  c += `  {${baseOff.get(t.id) || 0}, ${ia.off}, ${ia.n}, ${im.off}, ${im.n}, ${mm.off}, ${mm.n}, ${sa.off}, ${sa.n}, ${sm.off}, ${sm.n}, ${sc.off}, ${sc.n}, ${t.ctorMid}, ${nat}, ${t.cls.hasDtor ? 1 : 0}},\n`;
}
c += '};\n';
const nsRows = [];
for (const pm of parsed) {
  const a = members(pm.ns.attrIds.map(id => ({k: 'a', id, name: attrList[id - 1].name})));
  const m = members(pm.ns.methods.map(x => ({k: 'm', id: x.mid, name: x.name})));
  const k = constsOf(pm.ns.consts);
  const cl = members(pm.ns.classes.map(id => ({k: 'c', id, name: types[id - 1].name})));
  nsRows.push(`  {${q(pm.mod)}, ${a.off}, ${a.n}, ${m.off}, ${m.n}, ${k.off}, ${k.n}, ${cl.off}, ${cl.n}, ${q(pm.luacode)}},`);
}
// static nested classes inside classes (e.g. Defines.InternalEvents): members of kind 'c'
c += `const HsMember hs_members[${memRows.length + 1}] = {\n${memRows.map(x => `  {${q(x.name)}, '${x.k}', ${x.id}},`).join('\n')}\n  {0}\n};\n`;
c += `const HsConst hs_consts[${constRows.length + 1}] = {\n${constRows.map(k => k.s !== undefined ? `  {${q(k.name)}, 1, 0, ${q(k.s)}},` : `  {${q(k.name)}, 0, ${k.n ?? 0}, 0},`).join('\n')}\n  {0}\n};\n`;
c += `const int hs_nnamespaces = ${nsRows.length};\nconst HsNamespace hs_namespaces[] = {\n${nsRows.join('\n')}\n};\n`;
// nested static classes per class (SWIG registers class tables inside class tables)
c += `const short hs_sclasses[] = {0`;
let soff = 1; const sclassOff = new Map();
for (const t of types) if (t.cls && t.sclasses.length) { sclassOff.set(t.id, soff); for (const s of t.sclasses) { c += ',' + s; soff++; } c += ',0'; soff++; }
c += '};\n';
c += `const short hs_sclass_off[${types.length + 1}] = {0${types.map(t => ',' + (sclassOff.get(t.id) || 0)).join('')}};\n`;
fs.mkdirSync(path.join(repo, 'hs-lua/gen'), {recursive: true});
fs.writeFileSync(path.join(repo, 'hs-lua/gen/hs_api.c'), c);

// ---------------------------------------------------------------- emit Kotlin ids
const used = new Set();
const kname = (prefix, s) => { let n = prefix + ident(s); while (used.has(n)) n += '_'; used.add(n); return n; };
let k = `// Generated by tools/hs-bindgen/bindgen.mjs from SWIG 4.0.1's wrappers of FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07. Do not edit.
@file:Suppress("unused", "ObjectPropertyName")
package xyz.znix.xftl.hs.lua

/** Type ids, attribute codes and method ids of Hyperspace's Lua API, matching hs-lua/gen/hs_api.c. */
object LuaApi {
    const val TYPE_COUNT = ${types.length}
    const val ATTR_COUNT = ${attrList.length}
    const val METHOD_COUNT = ${methodList.length}
`;
for (const t of types) if (t.cls) k += `    const val T_${ident(t.name)} = ${t.id}\n`;
for (const a of attrList) { const owner = a.owner ? types[a.owner - 1].name : 'module'; k += `    const val A_${ident(owner)}_${ident(a.name)} = ${a.id}\n`; }
for (const m of methodList) k += `    const val ${kname('M_', m.fn.replace(/^_wrap_/, ''))} = ${m.id}\n`;
for (const [cls, obj] of [['InternalEvents', 'EV'], ['RenderEvents', 'RE'], ['Chain', 'CHAIN']]) {
  const t = types.find(x => x.name === cls);
  for (const kc of t.sconsts) k += `    const val ${obj}_${kc.name} = ${kc.n}
`;
}
const packChars = arr => arr.map(v => '\\u' + v.toString(16).padStart(4, '0')).join('');
const NL = '\n';
// Kotlin string literal: Kotlin has no \f or \v escapes, so every control/non-ASCII char is written as \uXXXX.
const kq = s => '"' + Array.from(s, c => {
  const n = c.charCodeAt(0);
  if (c === '"') return '\\"'; if (c === '\\') return '\\\\'; if (c === '$') return '\\$'; if (c === '\n') return '\\n';
  return n < 32 || n > 126 ? '\\u' + n.toString(16).padStart(4, '0') : c;
}).join('') + '"';
const chunked = str => { const parts = []; for (let i = 0; i < str.length; i += 12000) parts.push(kq(str.slice(i, i + 12000))); return 'arrayOf(' + parts.join(', ') + ').joinToString("")'; };
k += `    /** Static Lua type of object-valued attributes (0 for numbers, booleans, strings), one char per attribute id. */
    private const val ATTR_TYPE_S = "\\u0000${packChars(attrList.map(a => ['ptr', 'mem', 'val'].includes(a.kind) ? a.objType : 0))}"
    /** Attribute kind (1 number, 2 boolean, 3 string, 4 pointer, 5 member, 6 value), one char per attribute id. */
    private const val ATTR_KIND_S = "\\u0000${packChars(attrList.map(a => ({num: 1, bool: 2, str: 3, ptr: 4, mem: 5, val: 6}[a.kind] || 1)))}"
    /** std::vector wrappers: (vector type, size method, __getitem method, element type or 0 for numbers/strings) x n. */
    private const val VECTORS_S = "${packChars(types.filter(t => t.cls && /^vector_/.test(t.name)).flatMap(t => {
      const size = t.methods.find(m => m.name === 'size'), gi = t.methods.find(m => m.name === '__getitem');
      const el = gi ? methodList[gi.mid - 1].objType : null;
      return [t.id, size ? size.mid : 0, gi ? gi.mid : 0, el ? typeId.get(el) || 0 : 0];
    }))}"
    @JvmStatic fun vectors(): IntArray = IntArray(VECTORS_S.length) { VECTORS_S[it].code }
    /** Per class: type, attribute count, attribute ids..., base count, base types... (for generic struct adapters). */
    private val CLASS_ATTRS_S: String get() = ${chunked(types.filter(t => t.cls).flatMap(t => [t.id, t.attrIds.length, ...t.attrIds, t.baseIds.length, ...t.baseIds]).map(v => String.fromCharCode(v)).join(''))}
    @JvmStatic fun classAttrs(): IntArray { val s = CLASS_ATTRS_S; return IntArray(s.length) { s[it].code } }
    @JvmStatic fun attrType(a: Int): Int = ATTR_TYPE_S[a].code
    @JvmStatic fun attrKind(a: Int): Int = ATTR_KIND_S[a].code
    private val ATTR_NAMES_S: String get() = ${chunked(attrList.map(a => (a.owner ? types[a.owner - 1].name : 'module') + '.' + a.name).join(NL))}
    private val METHOD_NAMES_S: String get() = ${chunked(methodList.map(m => m.label).join(NL))}
    private val TYPE_NAMES_S: String get() = ${chunked(types.map(t => t.name || t.str).join(NL))}
    private var attrNames: List<String>? = null
    private var methodNames: List<String>? = null
    private var typeNames: List<String>? = null
    /** Names for capability reports and errors. */
    @JvmStatic fun attrName(a: Int): String { val l = attrNames ?: ATTR_NAMES_S.split('\\n').also { attrNames = it }; return l.getOrElse(a - 1) { "#" + a } }
    @JvmStatic fun methodName(m: Int): String { val l = methodNames ?: METHOD_NAMES_S.split('\\n').also { methodNames = it }; return l.getOrElse(m - 1) { "#" + m } }
    @JvmStatic fun typeName(t: Int): String { val l = typeNames ?: TYPE_NAMES_S.split('\\n').also { typeNames = it }; return l.getOrElse(t - 1) { "#" + t } }
}
`;
const kdir = path.join(repo, 'engine/src/main/java/xyz/znix/xftl/hs/lua');
fs.mkdirSync(kdir, {recursive: true});
fs.writeFileSync(path.join(kdir, 'LuaApi.kt'), k);

const ids = {types: {}, attrs: {}, methods: {}};
for (const t of types) if (t.cls) ids.types[t.name] = t.id;
for (const a of attrList) ids.attrs[(a.owner ? types[a.owner - 1].name : 'module') + '.' + a.name] = a.id;
for (const m of methodList) ids.methods[m.fn.replace(/^_wrap_/, '')] = m.id;
fs.writeFileSync(path.join(repo, 'hs-lua/gen/hs_ids.json'), JSON.stringify(ids));
const outDir = path.join(swigDir, '..');
fs.writeFileSync(path.join(outDir, 'catalog.json'), JSON.stringify({types: types.map(t => ({...t, cls: t.cls ? {name: t.cls.name, bases: t.cls.bases} : null})), attrs: attrList, methods: methodList, namespaces: parsed.map(p => ({mod: p.mod, ...p.ns})), unresolved}, null, 1));
console.log(`types ${types.length} (classes ${types.filter(t => t.cls).length}), attrs ${attrList.length}, methods ${methodList.length}, args ${argPool.length}, unresolved consts ${unresolved.length}`);
if (unresolved.length) console.log('unresolved:', unresolved.slice(0, 30).join('\n  '));

// ---------------------------------------------------------------- InternalEvents::GetEventInfo (signature, argCount, vararg)
{
  const h = fs.readFileSync(path.join(hsDir, 'lua', 'InternalEvents.h'), 'utf8');
  const body = h.slice(h.indexOf('eventInfo[Identifiers::UNKNOWN_MAX + 1] ='));
  const rows = [...body.matchAll(/\{\s*("(?:[^"\\]|\\.)*")\s*,\s*(\d+)\s*,\s*(true|false)\s*\}/g)].map(m => ({sig: JSON.parse(m[1]), args: +m[2], vararg: m[3] === 'true'}));
  let e = `/* Generated by tools/hs-bindgen/bindgen.mjs from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 lua/InternalEvents.h (GetEventInfo). Do not edit. */
static const struct { const char *sig; int args; int vararg; } hs_event_info[] = {
${rows.map(r => `  {${JSON.stringify(r.sig)}, ${r.args}, ${r.vararg ? 1 : 0}},`).join('\n')}
};
`;
  fs.writeFileSync(path.join(repo, 'hs-lua/gen/hs_events.h'), e);
  console.log('event info rows', rows.length);
}
