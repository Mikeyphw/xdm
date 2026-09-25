package staging

import (
	"os"
	"sync"
)

// File serializes all authoritative write-side operations against finalization.
// ReadAt/Stat remain available while frozen so verification can inspect stable bytes.
type File struct {
	inner *os.File
	mu    sync.RWMutex
}

func Wrap(file *os.File) *File {
	if file == nil {
		return nil
	}
	return &File{inner: file}
}

func (f *File) WriteAt(p []byte, off int64) (int, error) {
	f.mu.RLock()
	defer f.mu.RUnlock()
	return f.inner.WriteAt(p, off)
}
func (f *File) ReadAt(p []byte, off int64) (int, error) { return f.inner.ReadAt(p, off) }
func (f *File) Sync() error                             { f.mu.RLock(); defer f.mu.RUnlock(); return f.inner.Sync() }
func (f *File) Stat() (os.FileInfo, error)              { return f.inner.Stat() }
func (f *File) Truncate(size int64) error {
	f.mu.RLock()
	defer f.mu.RUnlock()
	return f.inner.Truncate(size)
}
func (f *File) Seek(offset int64, whence int) (int64, error) { return f.inner.Seek(offset, whence) }
func (f *File) Read(p []byte) (int, error)                   { return f.inner.Read(p) }
func (f *File) Name() string                                 { return f.inner.Name() }
func (f *File) Close() error                                 { return f.inner.Close() }

// Freeze waits for in-flight writes/sync/truncate operations to finish and blocks
// new ones until the returned thaw function is called.
func (f *File) Freeze() func() {
	f.mu.Lock()
	var once sync.Once
	return func() { once.Do(f.mu.Unlock) }
}
