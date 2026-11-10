// Post-authoring validation: existence, snip-tag correctness, relative-link integrity, mdoc counts.
const fs = require('fs');
const path = require('path');
const R = '/home/ubuntu/projects/libraries/grackle';
const snips = JSON.parse(fs.readFileSync(R + '/.docwork/snips.json', 'utf8'));
const pages = JSON.parse(fs.readFileSync(R + '/.docwork/pages.json', 'utf8'));
const allPagePaths = new Set(pages.map(p => p.path));

const expected = JSON.parse(fs.readFileSync(R + '/.docwork/expected-pages.json', 'utf8'));
let problems = 0;
const warn = (m) => { console.log('  ✗ ' + m); problems++; };

for (const rel of expected) {
  const abs = R + '/' + rel;
  if (!fs.existsSync(abs)) { warn(`MISSING FILE: ${rel}`); continue; }
  const txt = fs.readFileSync(abs, 'utf8');
  const words = txt.split(/\s+/).filter(Boolean).length;
  if (words < 80) warn(`TOO SHORT (${words} words): ${rel}`);

  // snip calls
  for (const m of txt.matchAll(/Output\.snip\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)/g)) {
    const [_, file, tag] = m;
    if (!snips[tag]) warn(`UNKNOWN SNIP TAG ${tag} in ${rel}`);
    else if (snips[tag].file !== file) warn(`SNIP FILE MISMATCH in ${rel}: ${tag} -> "${file}" but snips.json has "${snips[tag].file}"`);
  }

  // relative .md links
  for (const m of txt.matchAll(/\]\(([^)]+\.md)(#[^)]*)?\)/g)) {
    let link = m[1];
    if (/^https?:/.test(link)) continue;
    const resolved = path.normalize(path.join(path.dirname(rel), link));
    if (!fs.existsSync(R + '/' + resolved)) warn(`BROKEN LINK in ${rel}: ${link} -> ${resolved}`);
  }

  // H1 count
  const h1 = (txt.match(/^# /gm) || []).length;
  if (h1 !== 1) warn(`expected 1 H1, found ${h1}: ${rel}`);

  const mdoc = (txt.match(/```scala mdoc/g) || []).length;
  const passthru = (txt.match(/mdoc:passthrough/g) || []).length;
  console.log(`  ok ${rel}  [${words}w, ${mdoc} mdoc, ${passthru} snip-blocks]`);
}
console.log(`\nPROBLEMS: ${problems}`);
