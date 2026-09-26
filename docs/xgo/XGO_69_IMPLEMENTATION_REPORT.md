# XGO-69 — Android storage/publication broker

## Outcome

XGO-69 adds the Android publication broker boundary for SAF, MediaStore, and ContentResolver publication semantics. Go remains authoritative over the publication saga; Android performs only platform destination translation, permission/space checks, provider staging/commit, durable receipt persistence, and crash reconciliation replies.

## Delivered surface

- `engine/androidhost/publication.go`
  - canonical Android destination specs for document-tree and MediaStore-style destinations;
  - collision policies: fail, replace, rename;
  - persistable grant validation;
  - available-space failure mapping;
  - stage-to-provider commit model;
  - idempotent durable receipt store;
  - ambiguous provider-success crash reconciliation.
- `app/XDM.Android/.../publication/AndroidPublicationBroker.kt`
  - narrow Kotlin platform adapter for ContentResolver publication requests;
  - stage-to-provider and receipt-store boundary tokens;
  - no domain scheduler/engine decision logic.
- `engine/cmd/xgo-android-audit --mode android_publication_faults`
  - executable contract test for XGO-69 promises.

## Validation promises covered

- document tree destination translation;
- MediaStore-like destination translation;
- permission loss;
- collision handling;
- provider commit succeeds then process dies;
- idempotent restart reconciliation;
- disk/storage failure mapping.

## Boundary

This overlay intentionally does not reconnect Android scheduler, network policy, UI, media capture, or Room import. Those remain XGO-70+ work items.
