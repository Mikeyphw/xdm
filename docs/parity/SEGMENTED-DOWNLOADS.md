# Segmented download architecture

Eligible fresh downloads use four connections by default. The range probe must
return `206 Partial Content` with a complete length before segmentation is
allowed. Unknown-length and range-ignoring servers use the single-stream path.

Segments are stored beside the destination as:

```text
<destination>.segments/0000.part
<destination>.segments/0001.part
...
```

A retry resumes each segment from its actual on-disk length. Once every segment
matches its planned length, XDM concatenates them in index order into
`<destination>.part`, flushes it durably, removes the segment directory, and
uses the normal crash-safe finalization marker.

The implementation validates that ranges are contiguous, non-overlapping and
cover the full resource. Every segment response must preserve the probed total
length and validators.
