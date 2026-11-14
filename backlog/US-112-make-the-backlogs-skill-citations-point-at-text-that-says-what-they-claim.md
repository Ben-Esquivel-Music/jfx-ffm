# US-112 — Make the backlog's skill citations point at text that says what they claim

**Status:** 📋 Ready (filed 2026-11-13 from the US-075 game-toolkit-reviewer agent creation; criterion 2 needs the
owner's ruling) · **Priority:** Low (records only; no build or test reads these citations) · **Estimate:** S ·
**Found:** 2026-11-13, while the agent definition `.claude/agents/game-toolkit-reviewer.agent.md` was written for
US-075

## Story
As the maintainer, or an agent, picking up a backlog story,
I want every rule that a story cites from a skill under `.claude/skills/` to be where the citation says,
so that the story's constraints can be followed as written instead of searched for.

## Problem
Backlog stories cite the agent skills by line number, as `.claude/skills/<name>/SKILL.md:<a>-<b>`. The skills are
local to the owner's checkout and git-ignored (`.claude/**`), so a skill can change with no diff in this repository,
and some citations no longer hold or never did. On 2026-11-13:

- `openjfx-conventions/SKILL.md` has 158 lines. US-079, US-081 and the media stories US-100 to US-105 and US-107
  cite its `:86-89` as a ported-file block: a ported file keeps its upstream notices and carries a mark that it was
  altered. Lines 86-89 are the last bullet of "File headers" (the comment syntax of CMake files and poms) and the
  heading of "Formatting". The skill states no ported-file rule anywhere: it has no whole word "ported",
  "altered" or "provenance".
- US-090 cites `openjfx-conventions/SKILL.md:112-116` as an exception for named stories. Those lines are the test
  layout and the test module boundaries of "Java specifics".
- US-080, US-083, US-084, US-085 and US-088 cite `:157-164`, and US-092 and US-107 cite `:157-160`, past the end of
  the file.

The one check made when this story was filed was a perl script over `backlog/*.md`. It compared every
`.claude/skills/<name>/SKILL.md:<a>[-<b>]` citation, including follow-on `, :<c>-<d>` ranges, with the skill's
line count. It found 54 citations of four skills, 7 of them past the end of the file:

| Skill (lines) | Range | Against the file | Cited by (story:line) |
| --- | --- | --- | --- |
| `jfx-ffm-testing` (376) | `:92-93` | in range | US-066:88 |
| `jfx-ffm-testing` (376) | `:268-285` | in range | US-076:35, US-103:54, US-104:68, US-105:75, US-107:78 |
| `jfx-ffm-testing` (376) | `:268-286` | in range | US-097:93, US-098:54, US-099:89, US-100:77, US-101:64, US-102:112 |
| `jfx-ffm-testing` (376) | `:302-306` | in range | US-077:33 |
| `jfx-ffm-testing` (376) | `:318-320` | in range | US-094:30 |
| `jfx-rust-port` (340) | `:186-191` | in range | US-097:65, US-098:47, US-099:60 |
| `jfx-rust-port` (340) | `:186-193` | in range | US-103:42, US-104:54, US-105:57, US-107:60 |
| `jni-to-ffm-migration` (449) | `:91` | in range | US-103:45, US-104:57, US-105:63, US-107:70 |
| `jni-to-ffm-migration` (449) | `:91-107` | in range | US-100:55 |
| `jni-to-ffm-migration` (449) | `:148` | in range | US-090:62, US-092:46, US-096:73, US-099:58 |
| `jni-to-ffm-migration` (449) | `:148-153` | in range | US-107:63 |
| `jni-to-ffm-migration` (449) | `:148-203` | in range | US-088:30, US-089:34 |
| `jni-to-ffm-migration` (449) | `:150-151` | in range | US-083:22 |
| `jni-to-ffm-migration` (449) | `:176-184` | in range | US-066:39 |
| `jni-to-ffm-migration` (449) | `:348` | in range | US-085:38 |
| `openjfx-conventions` (158) | `:86-89` | in range | US-079:51, US-081:46, US-100:74, US-101:61, US-102:92, US-103:52, US-104:66, US-105:73, US-107:76 |
| `openjfx-conventions` (158) | `:112-116` | in range | US-090:53 |
| `openjfx-conventions` (158) | `:117` | in range | US-066:40 |
| `openjfx-conventions` (158) | `:157-160` | **past the end** | US-092:47, US-107:64 |
| `openjfx-conventions` (158) | `:157-164` | **past the end** | US-080:44, US-083:16, US-084:23, US-085:36, US-088:29 |

Only the three groups in the list above were read against the skill text. That the other citations are in range
says nothing about whether the text there is what the citing story claims; each still needs checking.

## Acceptance criteria
1. **Every citation holds.** Every `.claude/skills/*/SKILL.md` citation in `backlog/`, the 54 above and any added
   since, points at text that says what the citing story claims. Where the skill has a section for it, the citation
   names the section heading rather than a bare line number. No citation points past the end of its file.
2. **One place for the ported-file rule.** The rule that US-079, US-081 and the media stories rely on is stated in
   one place, and every story that relies on it cites that place. The owner decides whether that place is the
   `openjfx-conventions` skill, which is local and git-ignored, or the stories themselves, and the ruling is
   recorded here.

## Verification
From the repository root, in bash. It prints every citation with the skill's line count, and `PAST-EOF` for a range
past the end:
```
perl -ne '$l = $.; while (m{\.claude/skills/([\w.-]+)/SKILL\.md:(\d+)(?:-(\d+))?}g) {
    ($s, $x, $y) = ($1, $2, $3 // $2); open(F, "<", ".claude/skills/$s/SKILL.md") or die "$s\n"; @f = <F>;
    close F; printf "%s:%d\t%s\t:%s\t%d lines%s\n", $ARGV, $l, $s, $x == $y ? $x : "$x-$y", scalar @f,
    $y > @f ? "\tPAST-EOF" : "" } close ARGV if eof ARGV' backlog/*.md
```
Before the change it prints the 54 rows of the table, 7 of them with `PAST-EOF`. Afterwards it prints none with
`PAST-EOF`, and each row it still prints has been read against its citing story (criterion 1). Citations by section
heading are checked by reading the section.

## Out of scope
- Changing the substance of any rule, in a skill or in a story.

## Definition of Done
- Both criteria met, with the before and after output of the Verification command and the owner's ruling on
  criterion 2 stated in this story.
- Status set to ✅ Done with the date and the PR, and the row moved to the done table of `backlog/README.md`.
- Changes left in the working tree / a review branch — **no commits, merges or PRs without
  explicit approval from the repository owner.**

## Notes
- Related: US-075, whose `game-toolkit-reviewer` agent definition was being written when this was found. The
  citing stories are those of the table.
