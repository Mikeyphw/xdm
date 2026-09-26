#pragma once
#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    void* data;
    size_t len;
    uint64_t token;
} xdm_buffer_t;

int xdm_engine_create(const uint8_t* config, size_t config_len, uint64_t* out_handle);
int xdm_engine_command(uint64_t handle, const uint8_t* data, size_t data_len);
int xdm_engine_next_frame(uint64_t handle, int timeout_ms, xdm_buffer_t* out);
int xdm_engine_platform_reply(uint64_t handle, const uint8_t* data, size_t data_len);
int xdm_engine_metadata(uint64_t handle, xdm_buffer_t* out);
int xdm_engine_shutdown(uint64_t handle);
void xdm_buffer_free(xdm_buffer_t buffer);

#ifdef __cplusplus
}
#endif
