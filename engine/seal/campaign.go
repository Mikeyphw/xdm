package seal

// Operation names are intentionally language-neutral because the XGO-82
// campaign persists failing operation sequences as JSON regression fixtures.
type Operation string

const (
	Start          Operation = "start"
	Pause          Operation = "pause"
	Resume         Operation = "resume"
	Cancel         Operation = "cancel"
	Retry          Operation = "retry"
	MigrateBackend Operation = "migrate_backend"
	BackendLoss    Operation = "backend_loss"
	Crash          Operation = "crash"
	Recover        Operation = "recover"
	Verify         Operation = "verify"
	Publish        Operation = "publish"
	Delete         Operation = "delete"
)

var Operations = []Operation{
	Start, Pause, Resume, Cancel, Retry, MigrateBackend, BackendLoss,
	Crash, Recover, Verify, Publish, Delete,
}

// Invariant IDs are stable audit identifiers emitted by the state-machine
// campaign and referenced by the XGO-82 report.
var Invariants = []string{
	"INV-XGO-OWNERSHIP-001",
	"INV-XGO-GENERATION-001",
	"INV-XGO-TERMINAL-001",
	"INV-XGO-VERIFY-001",
	"INV-XGO-PUBLISH-001",
	"INV-XGO-RECOVERY-001",
	"INV-XGO-DELETE-001",
}
