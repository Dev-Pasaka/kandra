# ISS-101: No documented supported Ktor version range, and no test coverage against Ktor 3.x

**Status:** Fixed — documentation half done in GH #155; the CI/3.x-compatibility half was split out to GH #160, which
was closed 2026-10-20 by moving to Ktor 3.6 (Ktor 2.x dropped) and adding the `ktor-compat` CI matrix — see the
[0.5.0 changelog](../changelog/0.5.0.md)

## Problem

Filed as GH #155, found while verifying the kandraorm.com quickstart against the published 0.4.7
artifacts.

Kandra 0.4.7 was built and tested against Ktor 2.3.13 (`gradle/libs.versions.toml`'s
`ktor` entry, mirrored by `gradle.properties`' `ktorVersion`). Ktor 3.x had been out for a while, and new Ktor projects default to it, but nothing
in the repo stated which Ktor majors Kandra actually supported, or that 3.x hadn't been tried at
all.

## Fix (documentation half)

Added "built and tested against Ktor 2.3.x; Ktor 3.x untested" to `README.md`'s Quick Start section
and `kandra-ktor`'s own plugin KDoc (`Kandra.kt`) — matching the caveat kandraorm.com's docs had
already carried.

## Not done here — split to GH #160

The issue also asked for a real CI job testing against Ktor 3.x. Ktor 3 restructured parts of the
plugin-installation API relative to 2.x, so this is a genuine compatibility pass, not a version-
number bump — a different risk profile than a doc fix, and not something to bundle into the same
batch as ISS-097–ISS-100's unrelated bug fixes. Tracked separately as GH #160: try building
`kandra-ktor` against a Ktor 3.x dependency, decide the support story (single artifact, separate
`kandra-ktor3` artifact, or a documented "not supported" stance), and add CI coverage for whatever
is decided.

**Files:** `README.md`, `kandra-ktor/.../Kandra.kt` (KDoc).

See GH #155, [PR #161](https://github.com/Dev-Pasaka/kandra/pull/161); follow-up GH #160 (closed, shipped in 0.5.0: Ktor 3.6.0, CI also tests 3.5.2 and the latest 3.x).

> The "built and tested against Ktor 2.3.x" wording described above is as of 0.4.7 and is superseded: 0.5.0 supports Ktor 3.x only.
