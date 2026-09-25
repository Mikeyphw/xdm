package media

import (
	"bufio"
	"fmt"
	"net/url"
	"strconv"
	"strings"
)

var ErrInvalidHLSPlaylist = fmt.Errorf("invalid hls playlist")

type HLSPlaylistKind string

const (
	HLSPlaylistMaster HLSPlaylistKind = "master"
	HLSPlaylistMedia  HLSPlaylistKind = "media"
)

type HLSProtectionKind string

const (
	HLSProtectionNone      HLSProtectionKind = "none"
	HLSProtectionAES128    HLSProtectionKind = "aes-128"
	HLSProtectionSampleAES HLSProtectionKind = "sample-aes"
	HLSProtectionDRM       HLSProtectionKind = "drm"
	HLSProtectionUnknown   HLSProtectionKind = "unknown"
)

type HLSPlaylist struct {
	Kind                  HLSPlaylistKind
	BaseURL               string
	TargetDuration        float64
	PlaylistType          string
	MediaSequence         int64
	DiscontinuitySequence int64
	EndList               bool
	Variants              []HLSVariant
	Renditions            []HLSRendition
	Segments              []HLSSegment
	UnknownTags           []string
}

type HLSVariant struct {
	URI           string
	ResolvedURI   string
	Bandwidth     int
	Codecs        []string
	Resolution    string
	AudioGroup    string
	SubtitleGroup string
}

type HLSRendition struct {
	Type        string
	GroupID     string
	Name        string
	Language    string
	URI         string
	ResolvedURI string
	Default     bool
	AutoSelect  bool
}

type HLSMap struct {
	URI, ResolvedURI string
	Range            *ByteRange
}
type HLSKey struct {
	Method                          HLSProtectionKind
	URI, ResolvedURI, IV, KeyFormat string
}

type HLSSegment struct {
	URI                   string
	ResolvedURI           string
	Duration              float64
	Title                 string
	MediaSequence         int64
	DiscontinuitySequence int64
	Gap                   bool
	Range                 *ByteRange
	Map                   *HLSMap
	Key                   *HLSKey
	Protection            HLSProtectionKind
	UnknownTags           []string
}

func ParseHLSPlaylist(baseURL, text string) (HLSPlaylist, error) {
	base, err := normalizeHTTPURL(baseURL, "hls.base_url")
	if err != nil {
		return HLSPlaylist{}, err
	}
	p := HLSPlaylist{BaseURL: base, Kind: HLSPlaylistMedia}
	scanner := bufio.NewScanner(strings.NewReader(text))
	scanner.Buffer(make([]byte, 1024), MaxEnvelopeBytes)
	lineNo := 0
	sawHeader := false
	pendingVariant := map[string]string(nil)
	pendingDuration := 0.0
	pendingTitle := ""
	pendingGap := false
	var pendingRange *ByteRange
	var currentMap *HLSMap
	var currentKey *HLSKey
	discontinuity := int64(0)
	seq := int64(0)
	mediaSeqSet := false
	for scanner.Scan() {
		lineNo++
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		if lineNo == 1 && line != "#EXTM3U" {
			return HLSPlaylist{}, fmt.Errorf("%w: missing EXTM3U", ErrInvalidHLSPlaylist)
		}
		if line == "#EXTM3U" {
			sawHeader = true
			continue
		}
		if strings.HasPrefix(line, "#EXT-X-STREAM-INF:") {
			p.Kind = HLSPlaylistMaster
			pendingVariant = parseAttrs(strings.TrimPrefix(line, "#EXT-X-STREAM-INF:"))
			continue
		}
		if strings.HasPrefix(line, "#EXT-X-MEDIA:") {
			p.Kind = HLSPlaylistMaster
			r, err := parseHLSRendition(base, parseAttrs(strings.TrimPrefix(line, "#EXT-X-MEDIA:")))
			if err != nil {
				return HLSPlaylist{}, err
			}
			p.Renditions = append(p.Renditions, r)
			continue
		}
		if strings.HasPrefix(line, "#EXT-X-TARGETDURATION:") {
			p.TargetDuration, _ = strconv.ParseFloat(strings.TrimSpace(strings.TrimPrefix(line, "#EXT-X-TARGETDURATION:")), 64)
			continue
		}
		if strings.HasPrefix(line, "#EXT-X-PLAYLIST-TYPE:") {
			p.PlaylistType = strings.ToLower(strings.TrimSpace(strings.TrimPrefix(line, "#EXT-X-PLAYLIST-TYPE:")))
			continue
		}
		if strings.HasPrefix(line, "#EXT-X-MEDIA-SEQUENCE:") {
			p.MediaSequence, _ = strconv.ParseInt(strings.TrimSpace(strings.TrimPrefix(line, "#EXT-X-MEDIA-SEQUENCE:")), 10, 64)
			seq = p.MediaSequence
			mediaSeqSet = true
			continue
		}
		if strings.HasPrefix(line, "#EXT-X-DISCONTINUITY-SEQUENCE:") {
			p.DiscontinuitySequence, _ = strconv.ParseInt(strings.TrimSpace(strings.TrimPrefix(line, "#EXT-X-DISCONTINUITY-SEQUENCE:")), 10, 64)
			discontinuity = p.DiscontinuitySequence
			continue
		}
		if strings.HasPrefix(line, "#EXT-X-MAP:") {
			m, err := parseHLSMap(base, parseAttrs(strings.TrimPrefix(line, "#EXT-X-MAP:")))
			if err != nil {
				return HLSPlaylist{}, err
			}
			currentMap = &m
			continue
		}
		if strings.HasPrefix(line, "#EXT-X-KEY:") {
			k, err := parseHLSKey(base, parseAttrs(strings.TrimPrefix(line, "#EXT-X-KEY:")))
			if err != nil {
				return HLSPlaylist{}, err
			}
			currentKey = k
			continue
		}
		if strings.HasPrefix(line, "#EXT-X-BYTERANGE:") {
			r, err := parseByteRange(strings.TrimSpace(strings.TrimPrefix(line, "#EXT-X-BYTERANGE:")))
			if err != nil {
				return HLSPlaylist{}, err
			}
			pendingRange = r
			continue
		}
		if strings.HasPrefix(line, "#EXTINF:") {
			pendingDuration, pendingTitle = parseEXTINF(strings.TrimPrefix(line, "#EXTINF:"))
			continue
		}
		if line == "#EXT-X-GAP" {
			pendingGap = true
			continue
		}
		if line == "#EXT-X-ENDLIST" {
			p.EndList = true
			continue
		}
		if line == "#EXT-X-DISCONTINUITY" {
			discontinuity++
			continue
		}
		if strings.HasPrefix(line, "#") {
			p.UnknownTags = append(p.UnknownTags, safeUnknownHLSTag(line))
			continue
		}
		resolved, err := resolveURL(base, line)
		if err != nil {
			return HLSPlaylist{}, err
		}
		if pendingVariant != nil {
			v := parseHLSVariant(base, pendingVariant, line, resolved)
			p.Variants = append(p.Variants, v)
			pendingVariant = nil
			continue
		}
		if !mediaSeqSet && len(p.Segments) == 0 {
			seq = 0
		}
		seg := HLSSegment{URI: line, ResolvedURI: resolved, Duration: pendingDuration, Title: pendingTitle, MediaSequence: seq, DiscontinuitySequence: discontinuity, Gap: pendingGap, Range: cloneByteRange(pendingRange), Map: cloneHLSMap(currentMap), Key: cloneHLSKey(currentKey), Protection: protectionOfKey(currentKey)}
		p.Segments = append(p.Segments, seg)
		seq++
		pendingDuration = 0
		pendingTitle = ""
		pendingGap = false
		pendingRange = nil
	}
	if err := scanner.Err(); err != nil {
		return HLSPlaylist{}, err
	}
	if !sawHeader {
		return HLSPlaylist{}, fmt.Errorf("%w: missing EXTM3U", ErrInvalidHLSPlaylist)
	}
	return p, nil
}

func parseHLSVariant(base string, attrs map[string]string, uri, resolved string) HLSVariant {
	bw, _ := strconv.Atoi(attrs["BANDWIDTH"])
	codecs := []string{}
	for _, c := range strings.Split(attrs["CODECS"], ",") {
		if s := strings.TrimSpace(c); s != "" {
			codecs = append(codecs, s)
		}
	}
	return HLSVariant{URI: uri, ResolvedURI: resolved, Bandwidth: bw, Codecs: codecs, Resolution: attrs["RESOLUTION"], AudioGroup: attrs["AUDIO"], SubtitleGroup: attrs["SUBTITLES"]}
}
func parseHLSRendition(base string, attrs map[string]string) (HLSRendition, error) {
	resolved := ""
	if attrs["URI"] != "" {
		var err error
		resolved, err = resolveURL(base, attrs["URI"])
		if err != nil {
			return HLSRendition{}, err
		}
	}
	return HLSRendition{Type: strings.ToLower(attrs["TYPE"]), GroupID: attrs["GROUP-ID"], Name: attrs["NAME"], Language: strings.ToLower(attrs["LANGUAGE"]), URI: attrs["URI"], ResolvedURI: resolved, Default: yes(attrs["DEFAULT"]), AutoSelect: yes(attrs["AUTOSELECT"])}, nil
}
func parseHLSMap(base string, attrs map[string]string) (HLSMap, error) {
	if attrs["URI"] == "" {
		return HLSMap{}, fmt.Errorf("%w: map uri", ErrInvalidHLSPlaylist)
	}
	resolved, err := resolveURL(base, attrs["URI"])
	if err != nil {
		return HLSMap{}, err
	}
	r, _ := parseByteRange(attrs["BYTERANGE"])
	return HLSMap{URI: attrs["URI"], ResolvedURI: resolved, Range: r}, nil
}
func parseHLSKey(base string, attrs map[string]string) (*HLSKey, error) {
	method := classifyHLSProtection(attrs["METHOD"], attrs["KEYFORMAT"])
	if method == HLSProtectionNone {
		return nil, nil
	}
	resolved := ""
	if attrs["URI"] != "" {
		var err error
		resolved, err = resolveURL(base, attrs["URI"])
		if err != nil {
			return nil, err
		}
	}
	return &HLSKey{Method: method, URI: attrs["URI"], ResolvedURI: resolved, IV: attrs["IV"], KeyFormat: attrs["KEYFORMAT"]}, nil
}
func classifyHLSProtection(method, keyformat string) HLSProtectionKind {
	m := strings.ToUpper(strings.TrimSpace(method))
	kf := strings.ToLower(strings.TrimSpace(keyformat))
	switch {
	case m == "" || m == "NONE":
		return HLSProtectionNone
	case m == "AES-128" && (kf == "" || kf == "identity"):
		return HLSProtectionAES128
	case m == "SAMPLE-AES":
		return HLSProtectionSampleAES
	case strings.Contains(kf, "widevine") || strings.Contains(kf, "fairplay") || strings.Contains(kf, "playready"):
		return HLSProtectionDRM
	default:
		return HLSProtectionUnknown
	}
}
func protectionOfKey(k *HLSKey) HLSProtectionKind {
	if k == nil {
		return HLSProtectionNone
	}
	return k.Method
}
func parseEXTINF(raw string) (float64, string) {
	parts := strings.SplitN(raw, ",", 2)
	d, _ := strconv.ParseFloat(strings.TrimSpace(parts[0]), 64)
	title := ""
	if len(parts) > 1 {
		title = strings.TrimSpace(parts[1])
	}
	return d, title
}
func parseByteRange(raw string) (*ByteRange, error) {
	raw = strings.Trim(strings.TrimSpace(raw), "\"")
	if raw == "" {
		return nil, nil
	}
	parts := strings.SplitN(raw, "@", 2)
	length, err := strconv.ParseInt(strings.TrimSpace(parts[0]), 10, 64)
	if err != nil || length <= 0 {
		return nil, fmt.Errorf("%w: byterange", ErrInvalidHLSPlaylist)
	}
	offset := int64(0)
	if len(parts) == 2 {
		offset, err = strconv.ParseInt(strings.TrimSpace(parts[1]), 10, 64)
		if err != nil || offset < 0 {
			return nil, fmt.Errorf("%w: byterange", ErrInvalidHLSPlaylist)
		}
	}
	return &ByteRange{Offset: offset, Length: length}, nil
}
func resolveURL(base, ref string) (string, error) {
	b, err := url.Parse(base)
	if err != nil {
		return "", err
	}
	r, err := url.Parse(strings.TrimSpace(ref))
	if err != nil {
		return "", err
	}
	return b.ResolveReference(r).String(), nil
}
func parseAttrs(raw string) map[string]string {
	out := map[string]string{}
	var b strings.Builder
	inQuote := false
	parts := []string{}
	for _, r := range raw {
		switch r {
		case '"':
			inQuote = !inQuote
			b.WriteRune(r)
		case ',':
			if inQuote {
				b.WriteRune(r)
			} else {
				parts = append(parts, b.String())
				b.Reset()
			}
		default:
			b.WriteRune(r)
		}
	}
	if b.Len() > 0 {
		parts = append(parts, b.String())
	}
	for _, p := range parts {
		kv := strings.SplitN(p, "=", 2)
		if len(kv) != 2 {
			continue
		}
		k := strings.ToUpper(strings.TrimSpace(kv[0]))
		v := strings.TrimSpace(kv[1])
		v = strings.Trim(v, "\"")
		out[k] = v
	}
	return out
}
func yes(v string) bool { return strings.EqualFold(strings.TrimSpace(v), "YES") }
func safeUnknownHLSTag(tag string) string {
	if len(tag) > 512 {
		return tag[:512]
	}
	return tag
}
func cloneByteRange(r *ByteRange) *ByteRange {
	if r == nil {
		return nil
	}
	c := *r
	return &c
}
func cloneHLSMap(m *HLSMap) *HLSMap {
	if m == nil {
		return nil
	}
	c := *m
	c.Range = cloneByteRange(m.Range)
	return &c
}
func cloneHLSKey(k *HLSKey) *HLSKey {
	if k == nil {
		return nil
	}
	c := *k
	return &c
}
