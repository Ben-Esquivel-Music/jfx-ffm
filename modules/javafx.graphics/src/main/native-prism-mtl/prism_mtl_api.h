/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * prism_mtl_api.h - the flat C ABI of the prism_mtl library. macOS only.
 *
 * Nothing here names a JVM type and nothing calls back into Java: arguments are <stdint.h> scalars, opaque handles
 * and caller-owned memory, so no Java exception can cross this boundary. An NSException (the NSAsserts of
 * MetalPipelineManager.m) still unwinds through the caller's frames and terminates the process, as it did through
 * the JNI frames.
 *
 * Handles are Objective-C objects returned at +1: ctx is a MetalContext*, tex a MetalTexture*, rtt a MetalRTTexture*
 * (a MetalTexture subclass, accepted wherever tex is), shader a MetalShader*, mesh a MetalMesh*, material a
 * MetalPhongMaterial*, view a MetalMeshView*. mtl_context_release frees ctx. Every other handle is freed with
 * objc_release() from /usr/lib/libobjc.A.dylib, bound by Java: this library exports no release function for them.
 * MetalMesh and MetalMeshView override -release, and objc_release dispatches to the override. Handles are not
 * validated; a NULL handle behaves as 0L did behind the JNI, which for most functions is a message to nil.
 *
 * Threading: every function runs on the Prism render thread. Metal's completion handlers run on Metal's threads and
 * touch only the ring-buffer bookkeeping and the transient buffers inside this library.
 *
 * Booleans are int32_t: 0 is false, anything else is true.
 *
 * Memory: arrays, pixels, names and out-buffers are used during the call only; only the metallib of
 * mtl_context_create is kept. mtl_texture_update reads h rows of w pixels of the texture's pixel size, the first
 * at pixels + srcy * scan_bytes + srcx * pixel size and each further one scan_bytes on, and nothing else of pixels.
 * pixels can therefore be any address from which that arithmetic reaches the rows: a caller may pass a copy of just
 * those rows with srcx = srcy = 0, which MTLTextureCalls.SourceRows does for a Java array whose rows leave gaps.
 * The pixels of mtl_texture_update for a direct buffer, the metallib, and the pixel pointers of
 * mtl_texture_update_yuv422, mtl_rtt_init and mtl_rtt_read_pixels* are the BASE of the Java array or buffer: the
 * JNI used GetPrimitiveArrayCritical, Get<Type>ArrayElements or GetDirectBufferAddress and ignored buffer position
 * and array offset. mtl_shader_set_constants and mtl_mesh_build_geometry_* take a pointer to the first element they
 * read; for a direct FloatBuffer the JNI added position() * 4 bytes to the buffer address.
 *
 * Marks on the functions below, for choosing Linker.Option.critical(true):
 *   BLOCKS    commits a command buffer and can wait for the GPU: dispatch_semaphore_wait(DISPATCH_TIME_FOREVER)
 *             for a free ring buffer, or -waitUntilCompleted.
 *   ENCODES   encodes GPU work into the context's command buffer, creating it on demand with
 *             -[MTLCommandQueue commandBuffer], which waits while the queue already holds 64 non-completed command
 *             buffers. Command buffers that reserve no ring-buffer bytes are not throttled by the ring buffers, so
 *             that limit is reachable in principle. Every BLOCKS function also encodes.
 *   COMPILES  can build a pipeline state on first use.
 * None of these functions upcalls.
 */

#ifndef PRISM_MTL_API_H
#define PRISM_MTL_API_H

#include <stdint.h>

#define PRISM_MTL_EXPORT __attribute__((visibility("default")))

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Bumped whenever a symbol, a prototype or a constant changes. Java binds mtl_abi_version first and every other
 * symbol eagerly.
 */
#define PRISM_MTL_ABI_VERSION 1u

/* com.sun.prism.mtl.MTLContext.MTL_COMPMODE_*, formerly read from the javac -h header of MTLContext. */
enum MtlCompMode {
    MTL_COMPMODE_CLEAR   = 0,
    MTL_COMPMODE_SRC     = 1,
    MTL_COMPMODE_SRCOVER = 2,
    MTL_COMPMODE_DSTOUT  = 3,
    MTL_COMPMODE_ADD     = 4
};

/* com.sun.prism.mtl.MTLContext.CULL_*, formerly read from the javac -h header of MTLContext. */
enum MtlCullMode {
    MTL_CULL_BACK  = 110,
    MTL_CULL_FRONT = 111,
    MTL_CULL_NONE  = 112
};

/*
 * Indices of mtl_abi_constant, which answers -1 outside [0, MTL_ABI_CONSTANT_COUNT). The PFORMAT entries follow
 * the declaration order of enum PFormat in MetalTexture.h; mtl_texture_create compares PixelFormat.ordinal()
 * against those values.
 */
enum MtlAbiConstant {
    MTL_ABI_COMPMODE_CLEAR = 0,
    MTL_ABI_COMPMODE_SRC,
    MTL_ABI_COMPMODE_SRCOVER,
    MTL_ABI_COMPMODE_DSTOUT,
    MTL_ABI_COMPMODE_ADD,
    MTL_ABI_CULL_BACK,
    MTL_ABI_CULL_FRONT,
    MTL_ABI_CULL_NONE,
    MTL_ABI_SIZEOF_PRISM_SOURCE_VERTEX,     /* bytes per vertex of mtl_context_draw_indexed_quads' coords */
    MTL_ABI_NUM_OF_FLOATS_PER_VERTEX,       /* floats per vertex of mtl_mesh_build_geometry_*'s vb */
    MTL_ABI_MAX_NUM_QUADS,                  /* quads per index-buffer pass of mtl_context_draw_indexed_quads */
    MTL_ABI_MAX_NUM_LIGHTS,                 /* light slots of mtl_mesh_view_set_light */
    MTL_ABI_PFORMAT_INT_ARGB_PRE,
    MTL_ABI_PFORMAT_BYTE_RGBA_PRE,
    MTL_ABI_PFORMAT_BYTE_BGRA_PRE,
    MTL_ABI_PFORMAT_BYTE_RGB,
    MTL_ABI_PFORMAT_BYTE_GRAY,
    MTL_ABI_PFORMAT_BYTE_ALPHA,
    MTL_ABI_PFORMAT_MULTI_YV_12,
    MTL_ABI_PFORMAT_BYTE_APPL_422,
    MTL_ABI_PFORMAT_FLOAT_XYZW,
    MTL_ABI_CONSTANT_COUNT
};

PRISM_MTL_EXPORT uint32_t mtl_abi_version(void);                /* PRISM_MTL_ABI_VERSION */
PRISM_MTL_EXPORT int32_t  mtl_abi_constant(int32_t index);      /* enum MtlAbiConstant */

/* 1 when the default device supports MTLGPUFamilyMac2. The device is never released. */
PRISM_MTL_EXPORT int32_t  mtl_supports_metal(void);

/* ------------------------------------------------------------------------------------------------------------------
 * Context
 * ---------------------------------------------------------------------------------------------------------------- */

/*
 * metallib is borrowed, not copied, and must outlive ctx; size is the buffer's capacity, whatever its position.
 * NULL, with a message on stderr, when metallib is NULL, size <= 0 or the dispatch_data object cannot be created.
 * BLOCKS.
 */
PRISM_MTL_EXPORT void*   mtl_context_create(const void* metallib, int64_t size);
/* Sends -dealloc to ctx directly, not -release; NULL is ignored. BLOCKS (commits and waits). */
PRISM_MTL_EXPORT void    mtl_context_release(void* ctx);
PRISM_MTL_EXPORT void    mtl_context_commit(void* ctx);                                         /* BLOCKS */
/*
 * coords holds 7 floats per vertex (x, y, z, tu1, tv1, tu2, tv2), colors 4 bytes per vertex. Commits when a
 * transient MTLBuffer was taken since the last commit because a ring buffer was full. BLOCKS, COMPILES.
 */
PRISM_MTL_EXPORT void    mtl_context_draw_indexed_quads(void* ctx, const float* coords, const uint8_t* colors,
                                                        int32_t num_vertices);
/* Always 1. With depth_test, creates the depth texture of rtt on first use. */
PRISM_MTL_EXPORT int32_t mtl_context_update_render_target(void* ctx, void* rtt, int32_t depth_test);
PRISM_MTL_EXPORT void    mtl_context_set_clip_rect(void* ctx, int32_t x, int32_t y, int32_t w, int32_t h);
PRISM_MTL_EXPORT void    mtl_context_reset_clip_rect(void* ctx);
/* The matrix elements are narrowed to float. */
PRISM_MTL_EXPORT void    mtl_context_set_proj_view_matrix(void* ctx, int32_t depth_test,
        double m00, double m01, double m02, double m03, double m10, double m11, double m12, double m13,
        double m20, double m21, double m22, double m23, double m30, double m31, double m32, double m33);
PRISM_MTL_EXPORT void    mtl_context_set_transform(void* ctx,
        double m00, double m01, double m02, double m03, double m10, double m11, double m12, double m13,
        double m20, double m21, double m22, double m23, double m30, double m31, double m32, double m33);
PRISM_MTL_EXPORT void    mtl_context_set_world_transform(void* ctx,
        double m00, double m01, double m02, double m03, double m10, double m11, double m12, double m13,
        double m20, double m21, double m22, double m23, double m30, double m31, double m32, double m33);
/* Narrowed to float. */
PRISM_MTL_EXPORT void    mtl_context_set_camera_position(void* ctx, double x, double y, double z);
PRISM_MTL_EXPORT void    mtl_context_set_composite_mode(void* ctx, int32_t mode);            /* enum MtlCompMode */
/* Compares rtt with the render target last passed to mtl_context_update_render_target. */
PRISM_MTL_EXPORT int32_t mtl_context_is_current_rtt(void* ctx, void* rtt);
/* Copies the whole of src_rtt to the origin of dst_rtt; the coordinates are ignored. ENCODES. */
PRISM_MTL_EXPORT void    mtl_context_blit(void* ctx, void* src_rtt, void* dst_rtt,
                                          int32_t sx0, int32_t sy0, int32_t sx1, int32_t sy1,
                                          int32_t dx0, int32_t dy0, int32_t dx1, int32_t dy1);
/* The id<MTLCommandQueue> of ctx, borrowed: ctx owns it and releases it in mtl_context_release. */
PRISM_MTL_EXPORT void*   mtl_context_get_command_queue(void* ctx);
/* ENCODES, COMPILES. */
PRISM_MTL_EXPORT void    mtl_context_clear(void* ctx, float r, float g, float b, float a, int32_t clear_depth);

/* ------------------------------------------------------------------------------------------------------------------
 * Textures
 * ---------------------------------------------------------------------------------------------------------------- */

/* pixel_format is PixelFormat.ordinal(); unknown values give a BGRA8 texture. use_mipmap is ignored for 1x1. */
PRISM_MTL_EXPORT void*   mtl_texture_create(void* ctx, int32_t pixel_format, int32_t w, int32_t h, int32_t use_mipmap);
/*
 * Copies w x h pixels at (srcx, srcy) of pixels, scan_bytes per row and the texture's pixel size per pixel, to
 * (dstx, dsty). pixels is not checked: the JNI passed NULL for a heap buffer without an accessible array, and the
 * copy then crashed. Takes a transient MTLBuffer as large as the copy when it does not fit in what is left of the
 * 20 MB data ring buffer. ENCODES.
 */
PRISM_MTL_EXPORT void    mtl_texture_update(void* tex, const void* pixels,
                                            int32_t dstx, int32_t dsty, int32_t srcx, int32_t srcy,
                                            int32_t w, int32_t h, int32_t scan_bytes);
/*
 * Converts w x h UYVY pixels, read from the start of pixels with scan_bytes per row, into tex from its origin;
 * dstx, dsty, srcx and srcy are ignored. Allocates a w * h * 2 byte MTLBuffer. BLOCKS, COMPILES.
 */
PRISM_MTL_EXPORT void    mtl_texture_update_yuv422(void* tex, const uint8_t* pixels,
                                                   int32_t dstx, int32_t dsty, int32_t srcx, int32_t srcy,
                                                   int32_t w, int32_t h, int32_t scan_bytes);

/* pw x ph physical, cw x ch content; msaa adds a 4x multisample texture. */
PRISM_MTL_EXPORT void*   mtl_rtt_create(void* ctx, int32_t pw, int32_t ph, int32_t cw, int32_t ch, int32_t msaa);
/*
 * Wraps the id<MTLTexture> mtl_texture, which Glass owns, without retaining it. The content size is left 0, as it
 * was behind the JNI.
 */
PRISM_MTL_EXPORT void*   mtl_rtt_wrap(void* ctx, void* mtl_texture, int32_t pw, int32_t ph);
/*
 * Uploads physical width x height ints from pixels on the CPU. Java sizes its array by the content size, so under
 * prism.forcepow2 this reads past the array, as the JNI did.
 */
PRISM_MTL_EXPORT void    mtl_rtt_init(void* rtt, const int32_t* pixels);
/* Writes content width x height ints to dst, row by row from the physical-stride readback. BLOCKS. */
PRISM_MTL_EXPORT void    mtl_rtt_read_pixels(void* rtt, int32_t* dst);
/*
 * Writes content width x height ints to dst as one contiguous copy of the physical-stride readback, so rows shift
 * when the content is narrower than the texture. dst is not checked (NULL crashes, as it did for a heap IntBuffer
 * behind the JNI). BLOCKS.
 */
PRISM_MTL_EXPORT void    mtl_rtt_read_pixels_contiguous(void* rtt, int32_t* dst);

/* ------------------------------------------------------------------------------------------------------------------
 * Shaders
 * ---------------------------------------------------------------------------------------------------------------- */

/* name_utf16 holds name_len UTF-16 code units, no terminator. A NULL name_utf16 gives a nil function name. */
PRISM_MTL_EXPORT void*   mtl_shader_create(void* ctx, const uint16_t* name_utf16, int32_t name_len);
/*
 * The uniform name -> argument-buffer index table of shader, as the JSLC-generated dictionary holds it; these
 * indices are not the ones of the Java shader loaders. mtl_shader_uniform_count answers the number of entries.
 * mtl_shader_uniform_table enumerates them in the dictionary's fast-enumeration order: ids[i] gets the index of
 * entry i while i < ids_cap, and names gets each name as UTF-8 plus a NUL, appended while the whole entry fits in
 * names_cap bytes. It returns the bytes all names need, so a call with NULL buffers sizes them. A NULL shader
 * has no entries (the JNI returned a null map for it).
 */
PRISM_MTL_EXPORT int32_t mtl_shader_uniform_count(void* shader);
PRISM_MTL_EXPORT int32_t mtl_shader_uniform_table(void* shader, int32_t* ids, int32_t ids_cap,
                                                  char* names, int32_t names_cap);
PRISM_MTL_EXPORT void    mtl_shader_enable(void* shader);
PRISM_MTL_EXPORT void    mtl_shader_disable(void* shader);
/* wrap_mode is -1 (sampler default) or an MTLSamplerAddressMode (MTLContext.MTL_SAMPLER_ADDR_MODE_*). */
PRISM_MTL_EXPORT void    mtl_shader_set_texture(void* shader, int32_t tex_unit, int32_t uniform_id, void* tex,
                                                int32_t linear, int32_t wrap_mode);
PRISM_MTL_EXPORT void    mtl_shader_set_int(void* shader, int32_t uniform_id, int32_t i0);
PRISM_MTL_EXPORT void    mtl_shader_set_float1(void* shader, int32_t uniform_id, float f0);
PRISM_MTL_EXPORT void    mtl_shader_set_float2(void* shader, int32_t uniform_id, float f0, float f1);
PRISM_MTL_EXPORT void    mtl_shader_set_float3(void* shader, int32_t uniform_id, float f0, float f1, float f2);
PRISM_MTL_EXPORT void    mtl_shader_set_float4(void* shader, int32_t uniform_id,
                                               float f0, float f1, float f2, float f3);
/* Copies float_count floats. */
PRISM_MTL_EXPORT void    mtl_shader_set_constants(void* shader, int32_t uniform_id, const float* values,
                                                  int32_t float_count);

/* ------------------------------------------------------------------------------------------------------------------
 * 3D
 * ---------------------------------------------------------------------------------------------------------------- */

PRISM_MTL_EXPORT void*   mtl_mesh_create(void* ctx);
/*
 * vb_len floats of vb and ib_len indices of ib, where vb_capacity and ib_capacity are the Java array lengths. 0,
 * with nothing built, when a length is negative or exceeds its capacity, when an array is NULL or when mesh is
 * NULL; otherwise 1. Allocates vertex and index MTLBuffers of the input size when the vertex or index count
 * changes.
 */
PRISM_MTL_EXPORT int32_t mtl_mesh_build_geometry_short(void* mesh, const float* vb, int32_t vb_len,
                                                       int32_t vb_capacity, const uint16_t* ib, int32_t ib_len,
                                                       int32_t ib_capacity);
PRISM_MTL_EXPORT int32_t mtl_mesh_build_geometry_int(void* mesh, const float* vb, int32_t vb_len,
                                                     int32_t vb_capacity, const uint32_t* ib, int32_t ib_len,
                                                     int32_t ib_capacity);
PRISM_MTL_EXPORT void*   mtl_phong_material_create(void* ctx);
PRISM_MTL_EXPORT void    mtl_phong_material_set_diffuse_color(void* material, float r, float g, float b, float a);
PRISM_MTL_EXPORT void    mtl_phong_material_set_specular_color(void* material, int32_t set,
                                                               float r, float g, float b, float a);
/* tex may be NULL; its id<MTLTexture> is stored without retaining it. */
PRISM_MTL_EXPORT void    mtl_phong_material_set_map(void* material, int32_t map_type, void* tex);
/* mesh is stored without retaining it. */
PRISM_MTL_EXPORT void*   mtl_mesh_view_create(void* ctx, void* mesh);
/* cull is an MtlCullMode; any other value is stored as the MTLCullMode itself. */
PRISM_MTL_EXPORT void    mtl_mesh_view_set_culling_mode(void* view, int32_t cull);
/* material is stored without retaining it. */
PRISM_MTL_EXPORT void    mtl_mesh_view_set_material(void* view, void* material);
PRISM_MTL_EXPORT void    mtl_mesh_view_set_wireframe(void* view, int32_t wireframe);
PRISM_MTL_EXPORT void    mtl_mesh_view_set_ambient_light(void* view, float r, float g, float b);
/* An index outside [0, 3) is ignored. */
PRISM_MTL_EXPORT void    mtl_mesh_view_set_light(void* view, int32_t index, float x, float y, float z,
                                                 float r, float g, float b, float w,
                                                 float ca, float la, float qa, float is_attenuated, float max_range,
                                                 float dir_x, float dir_y, float dir_z,
                                                 float inner_angle, float outer_angle, float falloff);
/* ENCODES, COMPILES. */
PRISM_MTL_EXPORT void    mtl_mesh_view_render(void* ctx, void* view);

#ifdef __cplusplus
}
#endif

#endif /* PRISM_MTL_API_H */
