package metalink

import (
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"strconv"
	"strings"
	"unicode"

	domainrequest "github.com/subhra74/xdm/engine/domain/request"
	"github.com/subhra74/xdm/engine/domain/resource"
	"github.com/subhra74/xdm/engine/transfer/checksum"
)

const MaxDocumentBytes int64 = 2 << 20

var (
	ErrMalformed        = errors.New("malformed metalink document")
	ErrUnsupportedHash  = errors.New("unsupported metalink hash")
	ErrConflict         = errors.New("conflicting metalink metadata")
	ErrNoFiles          = errors.New("metalink document contains no files")
	ErrNoSources        = errors.New("metalink file contains no supported sources")
	ErrDocumentTooLarge = errors.New("metalink document exceeds size limit")
)

type Source struct {
	URL        string `json:"url"`
	Priority   int    `json:"priority,omitempty"`
	Preference int    `json:"preference,omitempty"`
	Location   string `json:"location,omitempty"`
	Order      int    `json:"order"`
}

type Metadata struct {
	SuggestedName    string   `json:"suggested_name,omitempty"`
	Identity         string   `json:"identity,omitempty"`
	Description      string   `json:"description,omitempty"`
	Version          string   `json:"version,omitempty"`
	Languages        []string `json:"languages,omitempty"`
	OperatingSystems []string `json:"operating_systems,omitempty"`
}

type File struct {
	Metadata  Metadata                 `json:"metadata"`
	Size      *int64                   `json:"size,omitempty"`
	Checksums []domainrequest.Checksum `json:"checksums,omitempty"`
	Sources   []Source                 `json:"sources"`
}

type Document struct {
	Files []File `json:"files"`
}

type Expansion struct {
	Metadata Metadata                    `json:"metadata"`
	Intent   domainrequest.NetworkIntent `json:"intent"`
}

type rawHash struct {
	Type  string `xml:"type,attr"`
	Value string `xml:",chardata"`
}

type rawURL struct {
	Priority   string `xml:"priority,attr"`
	Preference string `xml:"preference,attr"`
	Location   string `xml:"location,attr"`
	Value      string `xml:",chardata"`
}

type rawResources struct {
	URLs []rawURL `xml:"url"`
}

type rawVerification struct {
	Hashes []rawHash `xml:"hash"`
}

type rawFile struct {
	Name         string          `xml:"name,attr"`
	Sizes        []string        `xml:"size"`
	Hashes       []rawHash       `xml:"hash"`
	URLs         []rawURL        `xml:"url"`
	Resources    rawResources    `xml:"resources"`
	Verification rawVerification `xml:"verification"`
	Identity     string          `xml:"identity"`
	Description  string          `xml:"description"`
	Version      string          `xml:"version"`
	Languages    []string        `xml:"language"`
	OS           []string        `xml:"os"`
}

type rawFiles struct {
	Files []rawFile `xml:"file"`
}

type rawDocument struct {
	XMLName     xml.Name  `xml:"metalink"`
	Files       []rawFile `xml:"file"`
	LegacyFiles rawFiles  `xml:"files"`
}

func cleanMetadata(value string) (string, error) {
	value = strings.TrimSpace(value)
	if len(value) > 4096 {
		return "", fmt.Errorf("%w: metadata value too long", ErrMalformed)
	}
	for _, r := range value {
		if unicode.IsControl(r) && r != '\t' && r != '\n' && r != '\r' {
			return "", fmt.Errorf("%w: control character in metadata", ErrMalformed)
		}
	}
	return value, nil
}

func validateFileName(value string) (string, error) {
	value, err := cleanMetadata(value)
	if err != nil || value == "" {
		return "", fmt.Errorf("%w: file name", ErrMalformed)
	}
	if strings.Contains(value, "\\") || strings.Contains(value, ":") || strings.HasPrefix(value, "/") || strings.HasPrefix(value, "./") || strings.HasPrefix(value, "../") || strings.Contains(value, "/../") || strings.HasSuffix(value, "/..") || strings.HasSuffix(value, "/") {
		return "", fmt.Errorf("%w: unsafe file name", ErrMalformed)
	}
	for _, part := range strings.Split(value, "/") {
		if part == "" || part == "." || part == ".." {
			return "", fmt.Errorf("%w: unsafe file name", ErrMalformed)
		}
	}
	return value, nil
}

func cleanMetadataList(values []string) ([]string, error) {
	out := make([]string, 0, len(values))
	seen := map[string]struct{}{}
	for _, raw := range values {
		v, err := cleanMetadata(raw)
		if err != nil {
			return nil, err
		}
		if v == "" {
			continue
		}
		if _, ok := seen[v]; ok {
			continue
		}
		seen[v] = struct{}{}
		out = append(out, v)
	}
	return out, nil
}

func parsePositiveHint(raw string, field string) (int, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return 0, nil
	}
	v, err := strconv.Atoi(raw)
	if err != nil || v <= 0 {
		return 0, fmt.Errorf("%w: invalid %s", ErrMalformed, field)
	}
	return v, nil
}

func parseSize(values []string) (*int64, error) {
	var out *int64
	for _, raw := range values {
		raw = strings.TrimSpace(raw)
		if raw == "" {
			continue
		}
		v, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || v < 0 {
			return nil, fmt.Errorf("%w: invalid size", ErrMalformed)
		}
		if out != nil && *out != v {
			return nil, fmt.Errorf("%w: size %d vs %d", ErrConflict, *out, v)
		}
		copyV := v
		out = &copyV
	}
	return out, nil
}

func parseChecksums(values []rawHash) ([]domainrequest.Checksum, error) {
	byAlgorithm := map[string]string{}
	order := []string{}
	for _, raw := range values {
		n, err := checksum.NormalizeExpected(checksum.SourceMetalink, raw.Type, raw.Value)
		if err != nil {
			if errors.Is(err, checksum.ErrUnsupportedAlgorithm) {
				return nil, fmt.Errorf("%w: %s", ErrUnsupportedHash, strings.TrimSpace(raw.Type))
			}
			return nil, fmt.Errorf("%w: %v", ErrMalformed, err)
		}
		alg := string(n.Algorithm)
		if existing, ok := byAlgorithm[alg]; ok {
			if existing != n.Hex {
				return nil, fmt.Errorf("%w: %s hash", ErrConflict, alg)
			}
			continue
		}
		byAlgorithm[alg] = n.Hex
		order = append(order, alg)
	}
	out := make([]domainrequest.Checksum, 0, len(order))
	for _, alg := range order {
		out = append(out, domainrequest.Checksum{Algorithm: alg, Digest: byAlgorithm[alg]})
	}
	return out, nil
}

func parseSources(values []rawURL) ([]Source, error) {
	out := make([]Source, 0, len(values))
	for i, raw := range values {
		value := strings.TrimSpace(raw.Value)
		if value == "" {
			continue
		}
		priority, err := parsePositiveHint(raw.Priority, "priority")
		if err != nil {
			return nil, err
		}
		preference, err := parsePositiveHint(raw.Preference, "preference")
		if err != nil {
			return nil, err
		}
		location, err := cleanMetadata(raw.Location)
		if err != nil {
			return nil, err
		}
		out = append(out, Source{URL: value, Priority: priority, Preference: preference, Location: location, Order: i})
	}
	if len(out) == 0 {
		return nil, ErrNoSources
	}
	return out, nil
}

func Parse(r io.Reader) (Document, error) {
	if r == nil {
		return Document{}, fmt.Errorf("%w: nil reader", ErrMalformed)
	}
	raw, err := io.ReadAll(io.LimitReader(r, MaxDocumentBytes+1))
	if err != nil {
		return Document{}, fmt.Errorf("%w: %v", ErrMalformed, err)
	}
	if int64(len(raw)) > MaxDocumentBytes {
		return Document{}, ErrDocumentTooLarge
	}
	var parsed rawDocument
	decoder := xml.NewDecoder(strings.NewReader(string(raw)))
	decoder.Strict = true
	if err := decoder.Decode(&parsed); err != nil {
		return Document{}, fmt.Errorf("%w: %v", ErrMalformed, err)
	}
	if parsed.XMLName.Local != "metalink" {
		return Document{}, fmt.Errorf("%w: root element", ErrMalformed)
	}
	files := append(append([]rawFile(nil), parsed.Files...), parsed.LegacyFiles.Files...)
	if len(files) == 0 {
		return Document{}, ErrNoFiles
	}
	doc := Document{Files: make([]File, 0, len(files))}
	seenNames := map[string]struct{}{}
	for _, rawFile := range files {
		name, err := validateFileName(rawFile.Name)
		if err != nil {
			return Document{}, err
		}
		if _, exists := seenNames[name]; exists {
			return Document{}, fmt.Errorf("%w: duplicate file name %q", ErrConflict, name)
		}
		seenNames[name] = struct{}{}
		identity, err := cleanMetadata(rawFile.Identity)
		if err != nil {
			return Document{}, err
		}
		description, err := cleanMetadata(rawFile.Description)
		if err != nil {
			return Document{}, err
		}
		version, err := cleanMetadata(rawFile.Version)
		if err != nil {
			return Document{}, err
		}
		languages, err := cleanMetadataList(rawFile.Languages)
		if err != nil {
			return Document{}, err
		}
		operatingSystems, err := cleanMetadataList(rawFile.OS)
		if err != nil {
			return Document{}, err
		}
		size, err := parseSize(rawFile.Sizes)
		if err != nil {
			return Document{}, err
		}
		hashes := append(append([]rawHash(nil), rawFile.Hashes...), rawFile.Verification.Hashes...)
		checksums, err := parseChecksums(hashes)
		if err != nil {
			return Document{}, err
		}
		urls := append(append([]rawURL(nil), rawFile.URLs...), rawFile.Resources.URLs...)
		sources, err := parseSources(urls)
		if err != nil {
			return Document{}, err
		}
		doc.Files = append(doc.Files, File{
			Metadata: Metadata{SuggestedName: name, Identity: identity, Description: description, Version: version, Languages: languages, OperatingSystems: operatingSystems},
			Size:     size, Checksums: checksums, Sources: sources,
		})
	}
	return doc, nil
}

func betterSource(a, b Source) bool {
	if a.Priority != 0 || b.Priority != 0 {
		ap, bp := a.Priority, b.Priority
		if ap == 0 {
			ap = 1_000_000
		}
		if bp == 0 {
			bp = 1_000_000
		}
		if ap != bp {
			return ap < bp
		}
	}
	if a.Preference != 0 || b.Preference != 0 {
		if a.Preference != b.Preference {
			return a.Preference > b.Preference
		}
	}
	return a.Order < b.Order
}

func orderedSources(in []Source) []Source {
	out := append([]Source(nil), in...)
	for i := 1; i < len(out); i++ {
		for j := i; j > 0 && betterSource(out[j], out[j-1]); j-- {
			out[j], out[j-1] = out[j-1], out[j]
		}
	}
	return out
}

func mergeChecksums(existing, incoming []domainrequest.Checksum) ([]domainrequest.Checksum, error) {
	out := append([]domainrequest.Checksum(nil), existing...)
	byAlg := map[string]string{}
	for _, c := range existing {
		n, err := checksum.NormalizeExpected(checksum.SourceMetadata, c.Algorithm, c.Digest)
		if err != nil {
			return nil, fmt.Errorf("%w: existing checksum: %v", ErrConflict, err)
		}
		byAlg[string(n.Algorithm)] = n.Hex
	}
	for _, c := range incoming {
		n, err := checksum.NormalizeExpected(checksum.SourceMetalink, c.Algorithm, c.Digest)
		if err != nil {
			return nil, err
		}
		alg := string(n.Algorithm)
		if prior, ok := byAlg[alg]; ok {
			if prior != n.Hex {
				return nil, fmt.Errorf("%w: %s hash", ErrConflict, alg)
			}
			continue
		}
		out = append(out, domainrequest.Checksum{Algorithm: alg, Digest: n.Hex})
		byAlg[alg] = n.Hex
	}
	return out, nil
}

func childResource(parent resource.Identity, fileName string) (resource.Identity, error) {
	return resource.DeriveIdentity("metalink-child", parent.String()+"\x00"+fileName)
}

// Expand converts each Metalink file into the ordinary canonical NetworkIntent.
// The template contributes safe request policy (headers, credential references,
// backend preference, approvals); Metalink contributes transport sources,
// expected size/checksums and descriptive metadata. It never creates a separate
// Metalink execution path.
func Expand(template domainrequest.NetworkIntent, doc Document) ([]Expansion, error) {
	if len(doc.Files) == 0 {
		return nil, ErrNoFiles
	}
	if template.Resource.IsZero() {
		return nil, fmt.Errorf("%w: zero template resource", ErrMalformed)
	}
	out := make([]Expansion, 0, len(doc.Files))
	for _, file := range doc.Files {
		if len(file.Sources) == 0 {
			return nil, ErrNoSources
		}
		sources := orderedSources(file.Sources)
		res := template.Resource
		if len(doc.Files) > 1 {
			var err error
			res, err = childResource(template.Resource, file.Metadata.SuggestedName)
			if err != nil {
				return nil, err
			}
		}
		if template.ExpectedLength != nil && file.Size != nil && *template.ExpectedLength != *file.Size {
			return nil, fmt.Errorf("%w: expected size %d vs %d", ErrConflict, *template.ExpectedLength, *file.Size)
		}
		length := template.ExpectedLength
		if length == nil && file.Size != nil {
			v := *file.Size
			length = &v
		}
		checksums, err := mergeChecksums(template.Checksums, file.Checksums)
		if err != nil {
			return nil, err
		}
		mirrors := make([]string, 0, len(sources)-1)
		for _, source := range sources[1:] {
			mirrors = append(mirrors, source.URL)
		}
		candidate := template
		candidate.Resource = res
		candidate.TransportURL = sources[0].URL
		candidate.Method = "GET"
		candidate.Body = nil
		candidate.Mirrors = mirrors
		candidate.ExpectedLength = length
		candidate.Checksums = checksums
		canonical, err := domainrequest.NewNetworkIntent(candidate)
		if err != nil {
			return nil, fmt.Errorf("%w: source URL: %v", ErrMalformed, err)
		}
		out = append(out, Expansion{Metadata: file.Metadata, Intent: canonical})
	}
	return out, nil
}
