//go:build cgo && android

package sqlite

// Android intentionally embeds SQLite through the same mattn/go-sqlite3
// amalgamation strategy used by chrootctl. The Go-owned binding in db_cgo.go
// continues to call SQLite's stable C ABI, while this blank import contributes
// the sqlite3 C symbols to the final external link. This prevents Android from
// depending on hidden platform SQLite symbols or a separately packaged
// libsqlite3.so.
import _ "github.com/mattn/go-sqlite3"
