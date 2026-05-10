---
description: Rewrite markdown so it reads like a human wrote it, not an LLM
argument-hint: <file-path>
allowed-tools: Read, Edit, Bash(wc:*), Bash(test:*), Bash(head:*)
---

# Humanize markdown

Rewrite the markdown at `$ARGUMENTS` so it stops sounding like LLM output. Keep every factual claim, every code block, every link, every number — change only tone, structure, rhythm, and word choice.

Match the source document's language. Russian source → Russian output. English source → English output. Never translate.

---

## Hard refusals (check first)

Refuse to humanize and exit without touching the file if any of these match:

- Path matches `CHANGELOG.md` — Keep-a-Changelog format is strict; humanizing breaks tooling.
- Path matches `VERSION` — single-line file, nothing to humanize.
- Path under `.claude/tasks/` — task ledgers are an internal contract between roles, not human prose.
- Path under `docs/architecture/adr/` and the file is a template stub — ADRs follow Nygard's fixed structure.
- File starts with `<!-- generated -->` or `<!-- DO NOT EDIT -->` in the first 10 lines — auto-generated content.
- File is under 50 words — too short to need humanizing; you'd just be making it different, not better.

Print one line: `refused: <reason>` and stop.

---

## Invariants (never violate)

- Don't change content inside fenced code blocks, inline code, or HTML blocks.
- Don't change YAML frontmatter (the `---…---` block at the very top).
- Don't change link targets, anchor IDs, or image URLs.
- Don't change numbers, version strings, API names, file paths, env vars, SQL identifiers.
- Don't change the technical meaning of any sentence.
- Don't soften prescriptive verbs (`must` → `should`, `должен` → `можно`) unless the source intent was already soft.
- Don't add new content. You can cut, merge, and rewrite — never invent.

---

## Eliminate: structural tells

**Preamble.** Delete opening paragraphs that announce what the doc is about ("Этот документ описывает…", "В данной статье…", "This guide covers…"). Start with the first real claim.

**Outro.** Delete closing recap paragraphs ("В итоге…", "Таким образом…", "В заключение…", "In summary…", "To conclude…"). If the doc made the point, it doesn't need to repeat it.

**Header inflation.** Cut to the minimum:

- under 200 words: usually zero headers
- 200–800 words: H2 only
- over 800 words: max H2 + H3
- never H4 or deeper unless rendering a real outline

**Section bridges.** Delete "Перейдём к…", "Дальше рассмотрим…", "Now let's look at…", "В следующем разделе…". The next header is the bridge.

**Bullet abuse.** Lists of full-sentence bullets become prose. Bullets are for things you'd count on your fingers — steps, parameters, options, dependencies. Three short items often read better as one sentence: "X, Y, and Z."

**Bold-prefix bullets.** AI bullet pattern: `- **Term:** explanation`. Strip the bold prefix unless the term is genuinely being defined for the first time. Repeating `**Foo:**` six times across a list is a tell, not formatting.

**Table abuse.** Tables with 2 columns and under 4 rows → inline prose. Keep tables when comparing 3+ attributes across 4+ rows, or when columns are genuinely tabular data (params, status codes, env vars).

**Parallel-structure overload.** If every bullet starts with the same verb form or shape, break the pattern. Rewrite one as prose, drop a filler item, vary the openings.

**Quick-wins / Tips / Pro-tip callouts.** Almost always AI scaffolding. Delete the callout, fold the content (if real) into the surrounding text.

---

## Eliminate: lexical tells

Strip these. Rewrite the sentence around them — don't just remove the word.

### Russian

| Drop | Why |
|---|---|
| «Стоит отметить», «Следует упомянуть», «Важно понимать» | Filler. Just say the thing. |
| «По сути», «В целом», «Тем не менее», «При этом» (в начале предложения) | Hedging without content. |
| «Данный» → «этот» | Канцелярит. |
| «является» (как связка) → тире или конкретный глагол | «X является Y» → «X — Y». |
| «осуществить», «осуществлять» | → конкретный глагол: «сделать», «выполнить», «провести». |
| «позволяет», «обеспечивает», «предоставляет возможность» | Часто паразит. «Позволяет выполнить X» → «выполняет X». |
| «в рамках», «в части», «в плане», «в силу того что» | Канцелярит. Почти всегда выкидывается. |
| «посредством» → «через» / «с помощью» | Канцелярит. |
| «также», «кроме того», «помимо этого» (в начале абзаца) | Обычно удаляется. |
| «Безусловно», «Очевидно», «Разумеется» | Если очевидно — не нужно подчёркивать. |
| «не просто X, а Y» | Выбери одну формулировку. |

### English

| Drop | Why |
|---|---|
| "It's worth noting", "Note that", "Keep in mind", "Importantly" | Filler. Just say it. |
| "Essentially", "Basically", "Ultimately" | Hedging. |
| "Furthermore", "Moreover", "Additionally" at paragraph start | Almost always cut. |
| "leverage" → "use" | Corporate-speak. |
| "utilize" → "use" | Always. |
| "facilitate" → "help" / drop | Almost always vague. |
| "comprehensive", "robust", "powerful", "seamless", "cutting-edge" | Marketing adjectives. |
| "may potentially", "could possibly" | One hedge max. |
| "not just X, but Y" | Pick the actual claim. |
| "in order to" → "to" | Always. |
| "a wide range of" → drop or be specific | Vague. |
| "delve into" → "look at" / cut | LLM signature. |

### Em-dash rationing

LLMs put `—` in every other sentence. Replace ~70% with commas, periods, or parentheses. Keep some — the dash is a real tool — but don't let the doc be visually striped with them.

### Triple-list rationing

"X, Y, and Z" / "X, Y и Z" is the default LLM cadence. Convert some to two-item lists, two sentences, or drop the third item if it's filler.

---

## Eliminate: emphasis abuse

- Remove most **bold**. Keep bold only for terms being defined on first mention, true warnings, or table headers.
- Never bold the lead phrase of a bullet (see "Bold-prefix bullets" above).
- Remove italic used for casual emphasis. Keep italics for true titles, foreign-word usage, or technical terms first introduced.
- Delete decorative emoji in headers, bullets, and callouts: ✅ ❌ 🚀 📌 ⚡ 🔥 💡 🎯 ⭐ ⚠️ 📦 📊 🛠️. Keep emoji only inside fenced code, inline code, or quoted strings where they appear verbatim.
- `> [!NOTE]` / `> [!WARNING]` GitHub-style callouts: keep only if the note is genuinely a deviation from the main flow. Otherwise fold into prose.

---

## Eliminate: rhythm tells

Vary sentence length. LLMs default to 15–20 words for every sentence. Mix in 4-word sentences and 25-word sentences. Short ones at section starts work well.

Don't end every section with a takeaway sentence. It's fine to end on a fact mid-thought, the way humans do.

Don't use the same bullet shape twice in a row. If two consecutive bullets both open `Adds…`, rewrite one.

---

## Keep (don't "humanize" away)

- Lists that are genuinely enumerable: steps, parameters, CLI flags, dependencies, error codes.
- Tables comparing 3+ dimensions across 4+ rows.
- Code blocks, command output, error messages, stack traces — verbatim.
- Technical precision. If the original says "HikariCP pool of 50", don't make it "a large pool".
- Domain jargon that's correct for the audience (TX, JWT, idempotency, ADR).
- Cross-references (`see ADR-005`, `docs/architecture/05-communication.md §5.4.2`).

---

## Process

1. Read `$ARGUMENTS`. Note language, original word count (`wc -w`), and presence of frontmatter / code blocks / tables.
2. Run the refusal checks. If any match, print `refused: …` and stop.
3. Pass 1 — structural: cut preamble, cut outro, flatten headers, dissolve sentence-bullets into prose, drop 2×<4 tables, strip bold-prefix bullets.
4. Pass 2 — lexical: strip filler phrases per the tables; replace ~70% of em-dashes; break triple lists.
5. Pass 3 — emphasis: strip bold/italic/emoji per the rules.
6. Pass 4 — rhythm: vary sentence length, break parallel structures, drop redundant section closers.
7. Re-read top to bottom. Anything that still sounds like an FAQ entry, LinkedIn post, or release-marketing copy — rewrite.
8. Save in place (overwrite the source file via Edit).

Re-running on an already-humanized file should be a near-noop. If you find yourself making lots of changes on a second run, the rules above are wrong, not the file.

---

## Output

After saving, print exactly this block and nothing else. No preamble. No "I've successfully…".

```
humanized: <path>
words: <before> → <after>
top changes:
  - <one-line change>
  - <one-line change>
  - <one-line change>
```

If refused at step 2:

```
refused: <one-line reason>
```
