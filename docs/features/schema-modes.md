# Schema Modes (`kandra-ktor`)

| Mode | Behaviour |
|---|---|
| `SchemaMode.AUTO_CREATE` | `CREATE TABLE IF NOT EXISTS` on startup |
| `SchemaMode.AUTO_MIGRATE` | `CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ADD` for new columns; warns on unmapped Scylla columns |
| `SchemaMode.VALIDATE` | Verifies every entity column exists in Scylla; throws on mismatch |
| `SchemaMode.NONE` | No DDL; manage schema externally |
