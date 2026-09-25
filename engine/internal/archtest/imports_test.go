package archtest

import (
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

const module = "github.com/subhra74/xdm/engine/"

func TestFoundationAndDomainImportBoundaries(t *testing.T) {
	root := moduleRoot(t)
	forbiddenFoundation := []string{module + "domain/", module + "runtime/", module + "store/", module + "backend/", module + "media/"}
	forbiddenDomain := []string{module + "runtime/", module + "store/", module + "backend/", module + "media/", "android.", "androidx.", "Avalonia", "Microsoft.EntityFrameworkCore"}
	scanImports(t, filepath.Join(root, "foundation"), forbiddenFoundation)
	scanImports(t, filepath.Join(root, "domain"), forbiddenDomain)
}

func moduleRoot(t *testing.T) string {
	t.Helper()
	cwd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	root := filepath.Clean(filepath.Join(cwd, "..", ".."))
	if _, err := os.Stat(filepath.Join(root, "go.mod")); err != nil {
		t.Fatalf("module root: %v", err)
	}
	return root
}

func scanImports(t *testing.T, root string, forbidden []string) {
	t.Helper()
	err := filepath.WalkDir(root, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() || !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") {
			return nil
		}
		file, err := parser.ParseFile(token.NewFileSet(), path, nil, parser.ImportsOnly)
		if err != nil {
			return err
		}
		for _, spec := range file.Imports {
			imported, err := strconv.Unquote(spec.Path.Value)
			if err != nil {
				return err
			}
			for _, prefix := range forbidden {
				if strings.HasPrefix(imported, prefix) {
					t.Errorf("%s imports forbidden dependency %q", path, imported)
				}
			}
		}
		_ = ast.File{}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
}
