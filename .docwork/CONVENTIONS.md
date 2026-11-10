# Grackle docs — authoring conventions (READ FIRST)

You are writing one page of the Typelevel Grackle documentation. The site is built with
**Laika + sbt-typelevel-site**; pages are Markdown processed by **mdoc**. Follow these rules exactly.

## Voice & audience
- Second person ("you"), present tense, precise and friendly — Typelevel house style. No marketing fluff,
  no "simply/just/obviously". Define a term the first time you use it, then link to its reference/concept page.
- Assume a Scala developer comfortable with cats-effect basics. Do **not** re-teach GraphQL from zero, but
  do show the GraphQL for every example.

## Diátaxis discipline (match your page's type)
- **tutorial** — learning-oriented. End-to-end, runnable, builds one working thing start to finish. Reassuring,
  linear, no digressions. Link out (don't inline) deep theory. The reader should be able to copy-run every step.
- **how-to** — task-oriented recipe. Imperative ("To filter a field, …"). One concrete goal. Minimal theory;
  link to concepts/reference for the "why". Short: problem → steps (with code) → result → see-also.
- **explanation (concepts)** — understanding-oriented. Prose about mechanism and rationale. Diagrams welcome.
  Few signatures — point to reference for those. Answer "why is it built this way / what happens when I…".
- **reference** — information-oriented. Terse, exhaustive, scannable. Tables of types/constructors/signatures.
  No narrative, no tutorial. Snip real signatures from `modules/**/src/main`.

## Embedding REAL code — three mechanisms

### 1. Snip from a compiled source file (preferred for example mappings)
The repo has `grackle.docs.Output.snip(path, tag)` which inlines the lines between two `// #tag` markers
of a **real compiled source file**, as a ```scala block. Use it like this (this is literally what you write
in the markdown):

    ```scala mdoc:passthrough
    println(grackle.docs.Output.snip("modules/sql-core/src/test/scala/SqlWorldMapping.scala", "#world_typemappings"))
    ```

- Use the EXACT `path` + `#tag` pairs given in your page spec's `examples` list. Paths are repo-relative.
- The available tags and the EXACT text each renders are in `.docwork/snips.json`. READ IT so you can write
  prose that accurately describes the code the reader will see. You can also re-extract any tag yourself with
  `bash /home/ubuntu/projects/grackle/.docwork/snip.sh /home/ubuntu/projects/grackle/<path> '<tag>'`.
- Never paste snippet code by hand into a ```scala block — always go through `Output.snip` so it stays correct.
- Introduce every snip with a sentence of context, and follow it with an explanation of the key lines.

### 2. Inline compiled example (mdoc) — for tiny, self-contained illustrations
Use a fenced ```scala mdoc block. mdoc **compiles** it (and runs it) at build time; a mistake fails the build.

- ```scala mdoc — compile + run, show results inline (good for showing a query result JSON).
- ```scala mdoc:silent — compile + run, hide output.
- ```scala mdoc:compile-only — compile but DO NOT run (use for anything touching a database or external IO).
- Blocks on one page share a session: vals from earlier blocks are in scope later. Don't redefine a name twice.
- On the classpath: `grackle-core` (so `import grackle._`, `import grackle.syntax._`, `grackle.Query._`,
  `grackle.Predicate._`, `grackle.Value._`, `grackle.QueryCompiler._`), `cats-effect`, `fs2`, `io.circe`.
  Also the three compiled doc examples in package `grackle.docs`: `QuickStartMapping`, `FilterMapping`,
  `MutationSubscriptionMapping` — import them, e.g. `import grackle.docs.QuickStartMapping`.
- To run an `IO`: `import cats.effect.unsafe.implicits.global` then `prog.unsafeRunSync()`.
- NEVER write an mdoc block that needs a live database — the site build has no DB. Snip those from test sources instead.

### 3. Plain fenced blocks (not compiled) — for GraphQL, JSON, SDL, shell
- GraphQL queries: ```graphql . Responses: ```json . SDL you are only displaying: ```graphql .
- Shell/sbt: ```text or ```scala (for build.sbt snippets with `@VERSION@`).
- Use these only for things that are not Scala you could compile.

## Hard correctness invariants (getting these wrong is worse than omitting them)
1. **Nullability in the model is inverted vs SDL.** In GraphQL SDL `String` is nullable and `String!` is non-null.
   In Grackle's *internal* `Type` model a bare type is already non-null and optionality is an explicit
   `NullableType` wrapper. When writing SDL in examples use normal GraphQL (`String`, `String!`). Only mention the
   inverted model when explaining the `Type` ADT (concepts/reference). Don't confuse the two.
2. **Internal errors do not appear in the GraphQL `errors` array.** `Result` is `Success`/`Warning`/`Failure`/
   `InternalError`. `Failure`/`Warning` carry `Problem`s that surface in the response `errors`. `InternalError`
   is raised into the effect `F` instead. Never imply internal errors come back as JSON.
3. **No built-in Relay `Connection` type and no built-in websocket / graphql-ws transport.** Paging is assembled by
   hand from `Limit`/`Offset`/`OrderBy`/`Count` (and cursor predicates); subscriptions are plain `fs2.Stream`s you
   wire to a transport yourself. Do not imply ready-made helpers exist.
4. `schema"""..."""` (the interpolator, from `grackle.syntax._`) validates at **compile time** and yields a bare
   `Schema`. `Schema(text)` is the **runtime** factory returning `Result[Schema]`. Use the right one per context.
5. Grackle is a compiler/interpreter: query text → `QueryCompiler` (parse, type-check, elaborate) → `Query` algebra
   → `QueryInterpreter` (+ `Mapping`/`Cursor`) → `ProtoJson` → `Json`. SQL mappings further compile the algebra to SQL.

## Page structure
- Start with a single `# Title` H1 (matches the page title). Then a one-paragraph intro saying what the page covers
  and who it's for. No license header on `.md` files.
- Use `##`/`###` sections. Keep paragraphs tight.
- End every page (except pure reference tables) with a short **## See also** / **## Next steps** list of relative
  links to the most relevant other pages (tutorial→how-to→reference; how-to→concept).
- Cross-links are relative Markdown links to other `.md` files, e.g. `[elaboration](../concepts/compiler-elaboration.md)`.

## Don't
- Don't invent API. If you're unsure a method exists, read the source (`modules/**/src/main`) or the brief.
- Don't duplicate large content across pages — link instead. (Effects & composition intentionally appear as both a
  how-to recipe and a concept explanation: keep code in the how-to, keep the concept prose-focused.)
- Don't use `mdoc` blocks that need a DB, network, or long sleeps.
