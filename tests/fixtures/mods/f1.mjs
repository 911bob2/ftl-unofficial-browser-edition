// F1: our own small Slipstream mods (no game content) over a synthetic base "ftl.dat", exercising ftlman's per-file
// rules: append/rawappend/rawclobber, case-insensitive targets, plain XML rewriting, .txt CRLF and encodings, binary
// replace/add, skipped directories, ignored names, backslash paths and archive order. Archives are built
// deterministically, so ftlman ground truth (f1-expected.json, tools/mods/f1-ground-truth.mjs) stays reproducible.
import {zipSync} from '../../../web/vendor/fflate-0.8.3.mjs';

const enc = new TextEncoder();
const bin = (...values) => new Uint8Array(values);
// A real (silent, 8-bit mono 8 kHz) WAV, so browsers can decode mod audio end to end.
function wav(samples = 800) {
  const b = new Uint8Array(44 + samples), dv = new DataView(b.buffer), ascii = (o, s) => [...s].forEach((c, i) => { b[o + i] = c.charCodeAt(0); });
  ascii(0, 'RIFF'); dv.setUint32(4, 36 + samples, true); ascii(8, 'WAVEfmt '); dv.setUint32(16, 16, true); dv.setUint16(20, 1, true); dv.setUint16(22, 1, true);
  dv.setUint32(24, 8000, true); dv.setUint32(28, 8000, true); dv.setUint16(32, 1, true); dv.setUint16(34, 8, true); ascii(36, 'data'); dv.setUint32(40, samples, true);
  b.fill(128, 44); return b;
}
const utf16 = (text, le) => { const out = [le ? 0xff : 0xfe, le ? 0xfe : 0xff]; for (let i = 0; i < text.length; i++) { const c = text.charCodeAt(i); out.push(...(le ? [c & 255, c >> 8] : [c >> 8, c & 255])); } return new Uint8Array(out); };

export function f1Base() {
  const files = {
    'data/blueprints.xml': `<?xml version="1.0" encoding="utf-8"?>\n<FTL>\n<!-- weapons -->\n<weaponBlueprint name="F1_LASER_1"><type>LASER</type><damage>1</damage><desc>R&amp;D &lt;1&gt;</desc></weaponBlueprint>\n<weaponBlueprint name="F1_LASER_2"><type>LASER</type><damage>2</damage></weaponBlueprint>\n<blueprintList name="F1_LIST"><name>F1_LASER_1</name></blueprintList>\n</FTL>\n`,
    'data/events.xml': `<event name="F1_START"><text>Hello</text></event>\n<event name="F1_OTHER"><text>Other</text><choice><text>Ok</text></choice></event>\n`,
    'data/Text_Misc.xml': `<?xml version="1.0" encoding="utf-8"?>\n<FTL>\n<text name="a">A</text>\n</FTL>\n`,
    'data/animations.xml': `<FTL>\n<animSheet name="s" w="10" h="10" fw="1" fh="1">x.png</animSheet>\n</FTL>\n`,
    'data/odd.raw.xml': `<FTL><thing v="1"/></FTL>`,
    'data/plain.txt': 'base\nline\n',
    'data/remove_me~': 'base file whose name ftlman ignores',
    'img/base.png': bin(0x89, 0x50, 0x4e, 0x47, 1, 2, 3),
    'audio/waves/base.ogg': bin(0x4f, 0x67, 0x67, 0x53, 9, 9),
    'fonts/f.font': bin(1, 2, 3, 4),
    // silpkg (ftlman's ftl.dat writer) zeroes data it moved when the file is shorter than its grown index, which no
    // real ftl.dat is; this filler keeps the synthetic base large enough.
    'img/zz_filler.bin': new Uint8Array(1 << 20),
  };
  return new Map(Object.entries(files).map(([k, v]) => [k, typeof v === 'string' ? enc.encode(v) : v]));
}

const metadata = (title, version) => `<?xml version="1.0" encoding="UTF-8"?>\n<metadata>\n<title><![CDATA[ ${title} ]]></title>\n<version><![CDATA[ ${version} ]]></version>\n</metadata>\n`;
const zip = entries => zipSync(Object.fromEntries(entries.map(([name, data, level = 6]) => [name, [typeof data === 'string' ? enc.encode(data) : data, {level}]])), {mtime: new Date(Date.UTC(2026, 0, 1))});

export function f1Archives() {
  const a = zip([
    ['mod-appendix/', new Uint8Array(0), 0],
    ['mod-appendix/metadata.xml', metadata('F1 Content', '1.0')],
    ['data/blueprints.xml.append', `<?xml version="1.0" encoding="utf-8"?>\n<!-- ours -->\n<mod:findName name="F1_LASER_1"><mod:findLike type="damage"><mod:setValue>3</mod:setValue></mod:findLike><mod:setAttributes cost="10"/></mod:findName>\n<mod:findName name="F1_LIST"><mod-append:name>F1_ITEM_3</mod-append:name></mod:findName>\n<f1Item name="F1_ITEM_3"><type>LASER</type><damage>4</damage></f1Item>\n`],
    ['data/events.xml.append', `<mod:findName name="F1_OTHER" type="event"><mod:findLike type="choice"><mod:removeTag/></mod:findLike></mod:findName>\n<event name="F1_NEW"><text>New &amp; shiny</text></event>`],
    ['data/text_misc.xml.append', `<text name="b">B</text>`],
    ['data/missing.xml.append', `<nothing/>`],
    ['data/odd.rawappend.xml', `<mod:findLike type="thing"><mod:setAttributes v="2"/></mod:findLike>`],
    ['data/animations.xml.rawappend', `<?xml version="1.0"?>\n<animSheet name="f1_sheet" w="1" h="1" fw="1" fh="1">y.png</animSheet>`],
    ['data/rawc.xml.rawclobber', `﻿<FTL><raw   keep = 'as is'><!-- > --></raw></FTL>`],
    ['data/clobbered.rawclobber.xml', `<x>unchanged &bogus;</x>`],
    ['data/new_events.xml', `<?xml version="1.0" encoding="utf-8"?>\n<FTL>\n<!-- a > b -->\n<event  name = "N1" ><text>t</TEXT><choice/></event>\n<event name="N2"><text>unclosed`],
    ['data/utf16.xml', utf16(`<FTL><e t="é">ü</e></FTL>`, false)],
    ['data/notes.txt', '﻿line one\nline two\r\nline three\n'],
    ['data/utf16.txt', utf16('wide\ntext\n', true)],
    ['data/plain.txt', 'replaced\n'],
    ['data\\backslash.xml', '<b/>'],
    ['img/base.png', bin(0x89, 0x50, 0x4e, 0x47, 7, 7, 7), 0],
    ['img/new.png', bin(0x89, 0x50, 0x4e, 0x47, 5, 5, 5)],
    ['img/thumbs.db', bin(0)],
    ['img/sub/.DS_Store', bin(0)],
    ['data/remove_me~', 'mod copy'],
    ['audio/waves/new.ogg', bin(0x4f, 0x67, 0x67, 0x53, 1)],
    ['audio/waves/f1_tone.wav', wav()],
    ['README.txt', 'top-level\nfile\n'],
    ['extras/skipped.xml', '<skipped/>'],
    ['Data/upper.xml', '<skipped/>'],
  ]);
  const b = zip([
    ['mod-appendix/metadata.xml', metadata('F1 Override', '2')],
    ['data/blueprints.xml.append', `<mod:findName name="F1_ITEM_3"><mod-overwrite:damage>5</mod-overwrite:damage></mod:findName><mod:findName name="F1_LASER_2"><mod:removeTag/></mod:findName>`],
    ['data/New_Events.xml.append', `<mod:findName name="N2"><mod:setAttributes closed="yes"/></mod:findName>`],
    ['data/rawc.xml.append', `<appended/>`],
    ['img/new.png', bin(0x89, 0x50, 0x4e, 0x47, 6, 6, 6)],
    ['data/utf16.xml.append', `<mod:findLike type="e"><mod:setValue>v</mod:setValue></mod:findLike>`],
  ]);
  return [{name: 'f1-content.ftl', bytes: a}, {name: 'f1-override.zip', bytes: b}];
}
