export const meta = {
  name: 'grackle-docs-author',
  description: 'Author every new Grackle documentation page (Diátaxis), then audit & fix each against source',
  phases: [
    { title: 'Author', detail: 'one agent writes each page from its spec + brief + real snips' },
    { title: 'Audit', detail: 'an independent agent verifies each page against source and fixes it in place' },
  ],
}

const ROOT = '/home/ubuntu/projects/grackle'
const pages = typeof args === 'string' ? JSON.parse(args) : args

const REPORT = {
  type: 'object', additionalProperties: false,
  required: ['path','sectionsWritten','snipTagsUsed','mdocBlocks','crossLinks','concerns'],
  properties: {
    path: { type: 'string' },
    sectionsWritten: { type: 'array', items: { type: 'string' } },
    snipTagsUsed: { type: 'array', items: { type: 'string' } },
    mdocBlocks: { type: 'integer' },
    crossLinks: { type: 'array', items: { type: 'string' } },
    concerns: { type: 'array', items: { type: 'string' } },
  },
}

const READING = `Read these first (cat the JSON, Read the markdown):
1. ${ROOT}/.docwork/CONVENTIONS.md — authoring rules; follow exactly.
2. ${ROOT}/.docwork/pages.json — find the object whose .path === the PAGE PATH. That is your spec: title, diataxisType, purpose, audience, outline (ordered headings + notes), examples (kind + description + sourceFileAndRegion + needsDb).
3. ${ROOT}/.docwork/page-briefs.json — find the PAGE PATH key; it lists brief slugs. Read each ${ROOT}/.docwork/briefs/<slug>.json for source-grounded facts (exact signatures, concepts, gotchas, minimalExamples). These + real source are ground truth — never invent API.
4. ${ROOT}/.docwork/snips.json — map of every available snip tag → {file,lineCount,text}. Use ONLY tags present here.`

phase('Author')
const results = await pipeline(pages,
  // Stage 1: author
  (path) => agent(
`You are authoring ONE page of the Typelevel Grackle documentation. Repo root: ${ROOT}.
PAGE PATH: ${path}  — write the finished Markdown to ${ROOT}/${path} (create parent dirs if needed via Bash mkdir -p).

${READING}

Examples — how to embed:
- kind snip-existing / snip-new → emit:
  \`\`\`scala mdoc:passthrough
  println(grackle.docs.Output.snip("<repo-relative-path>", "<#tag>"))
  \`\`\`
  Use the exact path+tag from your spec's sourceFileAndRegion, cross-checked against snips.json. READ the snip text so your prose accurately describes those exact lines. NEVER paste snip text by hand.
- kind mdoc-inline → a compiled \`\`\`scala mdoc (or :silent / :compile-only) block. Classpath has grackle-core, cats-effect, fs2, io.circe, and the grackle.docs example objects (QuickStartMapping, FilterMapping, MutationSubscriptionMapping). NEVER require a database. Use :compile-only if you are unsure a block can run without external IO.
- kind graphql/json/sdl/diagram → plain fenced block (\`\`\`graphql / \`\`\`json) or an ASCII/Mermaid diagram.
- If a spec tag is missing from snips.json, do not use it — snip a real nearby tag or describe in prose, and record it in concerns.

Write the complete page now (single # H1, one-paragraph intro, the spec's sections, a final "## See also" with relative .md links to real pages from pages.json). Honor the Diátaxis altitude for your page type and every hard invariant in CONVENTIONS.md. Then return the structured report.`,
    { label: `author:${path.replace(/^docs\//,'')}`, phase: 'Author', schema: REPORT }),
  // Stage 2: audit & fix in place
  (r1, path) => agent(
`You are auditing and finishing ONE Grackle documentation page that another writer drafted. Repo root: ${ROOT}. PAGE: ${ROOT}/${path}.

${READING}
Also Read the current draft at ${ROOT}/${path}.

Verify and FIX IN PLACE (Edit/Write) — do not just report:
1. Correctness: every type/method/object named must exist in the briefs or real source. Grep to confirm: \`rg -n "<symbol>" ${ROOT}/modules/*/src/main\`. Fix or cut anything invented. Enforce the hard invariants in CONVENTIONS.md (nullability inversion; InternalError is NOT in the GraphQL errors array; no built-in Relay Connection / websocket transport; schema"" interpolator is compile-time → bare Schema vs Schema(text) → Result[Schema]).
2. Snips: every Output.snip("<path>","<tag>") must use a tag present in ${ROOT}/.docwork/snips.json with that same file. Fix mismatches; verify with \`bash ${ROOT}/.docwork/snip.sh ${ROOT}/<path> '<tag>'\`.
3. mdoc blocks: must compile — correct imports, valid Scala, no DB, no duplicate val names within the page. Downgrade to :compile-only if runtime is uncertain.
4. Diátaxis & structure: type-appropriate altitude, single H1, intro paragraph, a See-also section whose links resolve to real pages in pages.json (relative paths correct for this file's directory).
5. Prose: tighten, remove fluff, ensure every example is introduced and its key lines explained. Remove any TODOs.

Apply all fixes to ${ROOT}/${path} and return the final structured report for the page.`,
    { label: `audit:${path.replace(/^docs\//,'')}`, phase: 'Audit', schema: REPORT })
)

const ok = results.filter(Boolean)
log(`Authored+audited ${ok.length}/${pages.length} pages`)
const concerns = ok.flatMap(r => (r.concerns||[]).map(c => `${r.path}: ${c}`))
return { authored: ok.length, totalPages: pages.length, reports: ok, concerns }
