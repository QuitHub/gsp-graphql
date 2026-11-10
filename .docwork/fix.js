export const meta = {
  name: 'grackle-docs-fix',
  description: 'Apply reviewer findings to each flagged doc page, keeping the Laika/mdoc build green',
  phases: [ { title: 'Fix', detail: 'one agent per file applies its findings against real source' } ],
}
const ROOT = '/home/ubuntu/projects/grackle'
const files = typeof args === 'string' ? JSON.parse(args) : args

const REPORT = { type:'object', additionalProperties:false, required:['file','changes','buildSafe','notes'],
  properties:{ file:{type:'string'}, changes:{type:'array',items:{type:'string'}}, buildSafe:{type:'boolean'}, notes:{type:'array',items:{type:'string'}} } }

const PROMPT = (file) => [
  'You are fixing ONE Grackle documentation page per reviewer findings. Repo root: ' + ROOT + '. PAGE: ' + ROOT + '/' + file + '.',
  '',
  '1. Run:  cat ' + ROOT + '/.docwork/fix-assignments.json  and find the entry whose "file" equals "' + file + '". Apply EVERY finding in its "findings" array (each has a problem and a fix). Read ' + ROOT + '/.docwork/CONVENTIONS.md for house style.',
  '2. Verify against real source before changing: grep/read ' + ROOT + '/modules and ' + ROOT + '/demo. For any schema or code claim, match the REAL source exactly. In particular, for the Star Wars schema read ' + ROOT + '/demo/src/main/scala/demo/starwars/StarWarsMapping.scala and make any hand-written SDL match it verbatim (or drop the hand-written block in favour of the existing snip so there is one source of truth).',
  '3. Edit ' + ROOT + '/' + file + ' with the Edit tool. Preserve everything good; change only what the findings require.',
  '',
  'BUILD-SAFETY (the site is built with mdoc + Laika strict link validation; do NOT break it):',
  '- Do NOT alter any Output.snip(...) call file or tag argument; they are correct as-is.',
  '- Do NOT break any scala mdoc code block (keep them compiling; never require a database).',
  '- Any NEW internal link must point to a page that EXISTS (check ' + ROOT + '/.docwork/pages.json) using a correct RELATIVE path from this file directory. PREFER linking to the page itself without any #anchor; only use an anchor if you are certain of Laika slug rules (lowercase; every run of non-alphanumeric characters becomes a single hyphen). Unresolved links FAIL the build.',
  '- When adding a "See also" or "Next steps" section, use relative .md links to real pages only.',
  '4. Make each correction precisely as the finding describes (e.g. GenericMapping vs ValueMapping, prefer PathMatch over legacy PrefixedMapping, abstract class vs trait, replace stale tpolecat.github.io URLs with typelevel.org, add a missing Offset to a diagram, repair a malformed JSON block).',
  '',
  'Return the structured report listing each change you made.',
].join('\n')

phase('Fix')
const results = (await parallel(files.map(file => () =>
  agent(PROMPT(file), { label: 'fix:' + file.split('/').pop(), phase: 'Fix', schema: REPORT })
))).filter(Boolean)

log('Fixed ' + results.length + '/' + files.length + ' files')
return { fixed: results.length, results }
