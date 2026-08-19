/**
 * Defines the link anchor carried by a Wasmline Native engine KLIB.
 *
 * Date: 2026-08-19
 * Author: crowforkotlin
 */
#ifndef WASMLINE_ENGINE_LINK_H
#define WASMLINE_ENGINE_LINK_H

#ifdef __cplusplus
extern "C" {
#endif

/** Forces the selected Native engine archive into the final link. */
void wasmline_native_engine_link_anchor();

#ifdef __cplusplus
}
#endif

#endif
