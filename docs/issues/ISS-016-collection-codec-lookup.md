# ISS-016: Collection columns bound via raw `Class`-based codec lookup

**Status:** Investigated — not a bug, closed

## Problem

Raised during a pre-real-database-testing audit (2026-07-21): `StatementBuilder.setEncoded` binds
values with `set(idx, value, value::class.java as Class<Any>)`. For `List`/`Set`/`Map` columns,
`value::class.java` resolves to a concrete collection implementation class (e.g. `ArrayList`) with
no generic element-type information. The concern was that the DataStax driver's codec registry
needs a `GenericType` (with element types) to resolve collection codecs, and that a bare `Class`
lookup for collections is a well-known driver pitfall — a real risk since `FakeKandraSession` never
exercises the real codec registry, so this path had never actually been run.

## Resolution — verified against driver source, not a bug

Traced `SettableByIndex.set(int, V, Class<V>)` → `CodecRegistry.codecFor(DataType cqlType, Class<V>
javaType)` → `CachingCodecRegistry.createCodec(DataType, GenericType, boolean)` in
`java-driver-core:4.17.0`. When both a concrete `cqlType` (known here, because it comes from a
*prepared* statement bound to a real table's column definitions — e.g. `list<text>`) and an
unparameterized `GenericType` (raw `ArrayList.class`, no generics) are supplied, the registry
branches on `cqlType instanceof ListType/SetType/MapType` and derives the **element codec from the
CQL element type**, via `getElementCodecForCqlAndJavaType`, which falls back to
`codecFor(elementCqlType)` whenever the Java side has no `ParameterizedType` to inspect. In other
words: the schema-known CQL type supplies the element type info that Java-side generics erasure
destroys, and the driver was designed to handle exactly this case. No change needed.
