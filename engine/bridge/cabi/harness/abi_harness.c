#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "libxdmcore.h"

static int failures = 0;

static void expect_status(const char *name, int got, int want) {
    if (got != want) {
        fprintf(stderr, "%s: got status %d want %d\n", name, got, want);
        failures++;
    }
}

static int contains(const xdm_buffer_t *buf, const char *needle) {
    if (!buf || !buf->data || buf->len == 0) return 0;
    char *text = calloc(1, buf->len + 1);
    if (!text) return 0;
    memcpy(text, buf->data, buf->len);
    int ok = strstr(text, needle) != NULL;
    free(text);
    return ok;
}

int main(void) {
    uint64_t handle = 0;
    expect_status("create", xdm_engine_create(NULL, 0, &handle), XDM_OK);
    if (handle == 0) { fprintf(stderr, "create returned zero handle\n"); return 1; }

    xdm_buffer_t metadata = {0};
    expect_status("metadata", xdm_engine_metadata(handle, &metadata), XDM_OK);
    if (!contains(&metadata, "\"abi_major\":1") || !contains(&metadata, "\"major\":1")) {
        fprintf(stderr, "metadata missing version fields\n"); failures++;
    }
    xdm_buffer_free(metadata);
    xdm_buffer_free(metadata); /* tokenized free must be idempotent */

    const char *bad = "{";
    expect_status("malformed", xdm_engine_command(handle, (uint8_t*)bad, strlen(bad)), XDM_E_INVALID);

    const char *wrong = "{\"protocol\":{\"major\":99,\"minor\":0},\"command_id\":\"cmd_00000000000000000000000000000001\",\"operation_id\":\"op_00000000000000000000000000000001\",\"kind\":\"runtime.ping\"}";
    expect_status("wrong protocol", xdm_engine_command(handle, (uint8_t*)wrong, strlen(wrong)), XDM_E_PROTOCOL);

    const char *ping = "{\"protocol\":{\"major\":1,\"minor\":0},\"command_id\":\"cmd_00000000000000000000000000000001\",\"operation_id\":\"op_00000000000000000000000000000001\",\"kind\":\"runtime.ping\",\"payload\":{\"probe\":true}}";
    expect_status("ping", xdm_engine_command(handle, (uint8_t*)ping, strlen(ping)), XDM_OK);
    xdm_buffer_t frame = {0};
    expect_status("next ping frame", xdm_engine_next_frame(handle, 1000, &frame), XDM_OK);
    if (!contains(&frame, "runtime.pong")) { fprintf(stderr, "missing runtime.pong\n"); failures++; }
    xdm_buffer_free(frame);
    frame = (xdm_buffer_t){0};
    expect_status("next completion", xdm_engine_next_frame(handle, 1000, &frame), XDM_OK);
    if (!contains(&frame, "command.completed")) { fprintf(stderr, "missing command.completed\n"); failures++; }
    xdm_buffer_free(frame);

    expect_status("shutdown", xdm_engine_shutdown(handle), XDM_OK);
    expect_status("stale handle", xdm_engine_metadata(handle, &metadata), XDM_E_NOT_FOUND);

    const char *badcfg = "{\"protocol\":{\"major\":9,\"minor\":0}}";
    uint64_t rejected = 0;
    expect_status("create wrong protocol", xdm_engine_create((uint8_t*)badcfg, strlen(badcfg), &rejected), XDM_E_PROTOCOL);

    for (int i = 0; i < 256; i++) {
        uint64_t h = 0;
        if (xdm_engine_create(NULL, 0, &h) != XDM_OK || h == 0 || xdm_engine_shutdown(h) != XDM_OK) {
            fprintf(stderr, "lifecycle loop failed at %d\n", i); failures++; break;
        }
    }

    uint64_t pending = 0;
    expect_status("pending create", xdm_engine_create(NULL, 0, &pending), XDM_OK);
    const char *platform = "{\"protocol\":{\"major\":1,\"minor\":0},\"command_id\":\"cmd_00000000000000000000000000000003\",\"operation_id\":\"op_00000000000000000000000000000003\",\"kind\":\"runtime.platform_probe\",\"payload\":{}}";
    expect_status("platform probe", xdm_engine_command(pending, (uint8_t*)platform, strlen(platform)), XDM_OK);
    frame = (xdm_buffer_t){0};
    expect_status("platform request frame", xdm_engine_next_frame(pending, 1000, &frame), XDM_OK);
    if (!contains(&frame, "platform.request")) { fprintf(stderr, "missing platform.request\n"); failures++; }
    xdm_buffer_free(frame);
    expect_status("shutdown with outstanding platform request", xdm_engine_shutdown(pending), XDM_OK);

    if (failures) {
        fprintf(stderr, "ABI harness: %d failure(s)\n", failures);
        return 1;
    }
    printf("ABI harness PASS\n");
    return 0;
}
