#pragma once
#include <stdint.h>
#define PROP_VALUE_MAX 92
struct prop_info;
#ifdef __cplusplus
extern "C" {
#endif
const prop_info *__system_property_find(const char *);
int __system_property_get(const char *, char *);
int __system_property_foreach(void (*)(const prop_info *, void *), void *);
void __system_property_read_callback(const prop_info *, void (*)(void *, const char *, const char *, uint32_t), void *);
#ifdef __cplusplus
}
#endif
