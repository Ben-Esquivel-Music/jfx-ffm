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
 * The mtl_* functions of prism_mtl_api.h, except the uniform-table enumeration, which lives in MetalShader.m
 * because only that file may include the JSLC headers that define the uniform dictionaries.
 *
 * BOOL is a signed char, so an int32_t boolean is narrowed with != 0 before it reaches a BOOL parameter; bool
 * parameters convert that way by themselves.
 */

#import "MetalContext.h"
#import "MetalTexture.h"
#import "MetalRTTexture.h"
#import "MetalShader.h"
#import "MetalMesh.h"
#import "MetalMeshView.h"
#import "MetalPhongMaterial.h"
#import "prism_mtl_api.h"

_Static_assert(sizeof(PrismSourceVertex) == 7 * sizeof(float), "PrismSourceVertex must be 7 packed floats");

static const int32_t ABI_CONSTANTS[MTL_ABI_CONSTANT_COUNT] = {
    [MTL_ABI_COMPMODE_CLEAR]             = MTL_COMPMODE_CLEAR,
    [MTL_ABI_COMPMODE_SRC]               = MTL_COMPMODE_SRC,
    [MTL_ABI_COMPMODE_SRCOVER]           = MTL_COMPMODE_SRCOVER,
    [MTL_ABI_COMPMODE_DSTOUT]            = MTL_COMPMODE_DSTOUT,
    [MTL_ABI_COMPMODE_ADD]               = MTL_COMPMODE_ADD,
    [MTL_ABI_CULL_BACK]                  = MTL_CULL_BACK,
    [MTL_ABI_CULL_FRONT]                 = MTL_CULL_FRONT,
    [MTL_ABI_CULL_NONE]                  = MTL_CULL_NONE,
    [MTL_ABI_SIZEOF_PRISM_SOURCE_VERTEX] = (int32_t) sizeof(PrismSourceVertex),
    [MTL_ABI_NUM_OF_FLOATS_PER_VERTEX]   = NUM_OF_FLOATS_PER_VERTEX,
    [MTL_ABI_MAX_NUM_QUADS]              = MAX_NUM_QUADS,
    [MTL_ABI_MAX_NUM_LIGHTS]             = MAX_NUM_LIGHTS,
    [MTL_ABI_PFORMAT_INT_ARGB_PRE]       = PFORMAT_INT_ARGB_PRE,
    [MTL_ABI_PFORMAT_BYTE_RGBA_PRE]      = PFORMAT_BYTE_RGBA_PRE,
    [MTL_ABI_PFORMAT_BYTE_BGRA_PRE]      = PFORMAT_BYTE_BGRA_PRE,
    [MTL_ABI_PFORMAT_BYTE_RGB]           = PFORMAT_BYTE_RGB,
    [MTL_ABI_PFORMAT_BYTE_GRAY]          = PFORMAT_BYTE_GRAY,
    [MTL_ABI_PFORMAT_BYTE_ALPHA]         = PFORMAT_BYTE_ALPHA,
    [MTL_ABI_PFORMAT_MULTI_YV_12]        = PFORMAT_MULTI_YV_12,
    [MTL_ABI_PFORMAT_BYTE_APPL_422]      = PFORMAT_BYTE_APPL_422,
    [MTL_ABI_PFORMAT_FLOAT_XYZW]         = PFORMAT_FLOAT_XYZW
};

uint32_t mtl_abi_version(void)
{
    return PRISM_MTL_ABI_VERSION;
}

int32_t mtl_abi_constant(int32_t index)
{
    if (index < 0 || index >= MTL_ABI_CONSTANT_COUNT) {
        return -1;
    }
    return ABI_CONSTANTS[index];
}

int32_t mtl_supports_metal(void)
{
    id<MTLDevice> device = MTLCreateSystemDefaultDevice();
    // The Prism MTL pipeline requires MTLGPUFamilyMac2
    if ([device supportsFamily:MTLGPUFamilyMac2]) {
        return 1;
    }
    return 0;
}

// ** Context **

void* mtl_context_create(const void* metallib, int64_t size)
{
    if (metallib == NULL) {
        NSLog(@"MTLContext_nInitialize: shaderLibBuffer addr = NULL");
        return NULL;
    }

    if (size <= 0) {
        NSLog(@"MTLContext_nInitialize: shaderLibBuffer invalid capacity");
        return NULL;
    }

    // A no-op destructor: the caller owns metallib and keeps it alive for the context's lifetime.
    dispatch_data_t shaderLibData = dispatch_data_create(metallib, size,
            DISPATCH_QUEUE_SERIAL,
            ^(void) {});

    if (shaderLibData == nil) {
        NSLog(@"MTLContext_nInitialize: Unable to create a dispatch_data object");
        return NULL;
    }

    return [[MetalContext alloc] createContext:shaderLibData];
}

void mtl_context_release(void* ctx)
{
    MetalContext *context = (MetalContext *)ctx;
    if (context != nil) {
        [context dealloc];
    }
}

void mtl_context_commit(void* ctx)
{
    [(MetalContext *)ctx commitCurrentCommandBuffer];
}

void mtl_context_draw_indexed_quads(void* ctx, const float* coords, const uint8_t* colors, int32_t num_vertices)
{
    [(MetalContext *)ctx drawIndexedQuads:(const PrismSourceVertex *)coords
                                 ofColors:(const char *)colors
                              vertexCount:num_vertices];
}

int32_t mtl_context_update_render_target(void* ctx, void* rtt, int32_t depth_test)
{
    MetalContext *context = (MetalContext *)ctx;
    int ret = [context setRTT:(MetalRTTexture *)rtt];
    // If we create depth texture while creating RTT
    // then also current implementation works fine. So in future
    // if we see any performance/state impact we should move
    // depthTexture creation along with RTT creation,
    // implement or change in future if necessary.
    if (depth_test) {
        [context verifyDepthTexture];
    }
    return ret;
}

void mtl_context_set_clip_rect(void* ctx, int32_t x, int32_t y, int32_t w, int32_t h)
{
    [(MetalContext *)ctx setClipRect:x y:y width:w height:h];
}

void mtl_context_reset_clip_rect(void* ctx)
{
    [(MetalContext *)ctx resetClipRect];
}

void mtl_context_set_proj_view_matrix(void* ctx, int32_t depth_test,
        double m00, double m01, double m02, double m03, double m10, double m11, double m12, double m13,
        double m20, double m21, double m22, double m23, double m30, double m31, double m32, double m33)
{
    [(MetalContext *)ctx setProjViewMatrix:depth_test
        m00:m00 m01:m01 m02:m02 m03:m03
        m10:m10 m11:m11 m12:m12 m13:m13
        m20:m20 m21:m21 m22:m22 m23:m23
        m30:m30 m31:m31 m32:m32 m33:m33];
}

void mtl_context_set_transform(void* ctx,
        double m00, double m01, double m02, double m03, double m10, double m11, double m12, double m13,
        double m20, double m21, double m22, double m23, double m30, double m31, double m32, double m33)
{
    // Added separate nSetTransform because previously
    // we used to use nSetProjViewMatrix only and enabled depth test
    // by default. Also check whether we need to do anything else
    // apart from just updating projection view matrix,
    // implement or change in future if necessary.

    [(MetalContext *)ctx setProjViewMatrix:m00
        m01:m01 m02:m02 m03:m03
        m10:m10 m11:m11 m12:m12 m13:m13
        m20:m20 m21:m21 m22:m22 m23:m23
        m30:m30 m31:m31 m32:m32 m33:m33];
}

void mtl_context_set_world_transform(void* ctx,
        double m00, double m01, double m02, double m03, double m10, double m11, double m12, double m13,
        double m20, double m21, double m22, double m23, double m30, double m31, double m32, double m33)
{
    [(MetalContext *)ctx setWorldTransformMatrix:m00 m01:m01 m02:m02 m03:m03
        m10:m10 m11:m11 m12:m12 m13:m13
        m20:m20 m21:m21 m22:m22 m23:m23
        m30:m30 m31:m31 m32:m32 m33:m33];
}

void mtl_context_set_camera_position(void* ctx, double x, double y, double z)
{
    [(MetalContext *)ctx setCameraPosition:x y:y z:z];
}

void mtl_context_set_composite_mode(void* ctx, int32_t mode)
{
    [(MetalContext *)ctx setCompositeMode:mode];
}

int32_t mtl_context_is_current_rtt(void* ctx, void* rtt)
{
    return [(MetalContext *)ctx isCurrentRTT:(MetalRTTexture *)rtt];
}

void mtl_context_blit(void* ctx, void* src_rtt, void* dst_rtt,
                      int32_t sx0, int32_t sy0, int32_t sx1, int32_t sy1,
                      int32_t dx0, int32_t dy0, int32_t dx1, int32_t dy1)
{
    id<MTLTexture> src = [(MetalRTTexture *)src_rtt getTexture];
    id<MTLTexture> dst = [(MetalRTTexture *)dst_rtt getTexture];

    [(MetalContext *)ctx blit:src srcX0:sx0 srcY0:sy0 srcX1:sx1 srcY1:sy1
        dstTex:dst dstX0:dx0 dstY0:dy0 dstX1:dx1 dstY1:dy1];
}

// This enables sharing of MTLCommandQueue between PRISM and GLASS, if needed.
void* mtl_context_get_command_queue(void* ctx)
{
    return (void *)[(MetalContext *)ctx getCommandQueue];
}

void mtl_context_clear(void* ctx, float r, float g, float b, float a, int32_t clear_depth)
{
    [(MetalContext *)ctx clearRTT:r
                            green:g
                             blue:b
                            alpha:a
                       clearDepth:clear_depth];
}

// ** Textures **

void* mtl_texture_create(void* ctx, int32_t pixel_format, int32_t w, int32_t h, int32_t use_mipmap)
{
    return [[MetalTexture alloc] createTexture:(MetalContext *)ctx
                                       ofWidth:w
                                      ofHeight:h
                                   pixelFormat:pixel_format
                                     useMipMap:use_mipmap != 0];
}

void mtl_texture_update(void* tex, const void* pixels,
                        int32_t dstx, int32_t dsty, int32_t srcx, int32_t srcy,
                        int32_t w, int32_t h, int32_t scan_bytes)
{
    [(MetalTexture *)tex updateTexture:(void *)pixels
                                  dstX:dstx
                                  dstY:dsty
                                  srcX:srcx
                                  srcY:srcy
                                 width:w
                                height:h
                            scanStride:scan_bytes];
}

void mtl_texture_update_yuv422(void* tex, const uint8_t* pixels,
                               int32_t dstx, int32_t dsty, int32_t srcx, int32_t srcy,
                               int32_t w, int32_t h, int32_t scan_bytes)
{
    [(MetalTexture *)tex updateTextureYUV422:(char *)pixels
                                        dstX:dstx
                                        dstY:dsty
                                        srcX:srcx
                                        srcY:srcy
                                       width:w
                                      height:h
                                  scanStride:scan_bytes];
}

void* mtl_rtt_create(void* ctx, int32_t pw, int32_t ph, int32_t cw, int32_t ch, int32_t msaa)
{
    return [[MetalRTTexture alloc] createTexture:(MetalContext *)ctx
                                 ofPhysicalWidth:pw
                                ofPhysicalHeight:ph
                                  ofContentWidth:cw
                                 ofContentHeight:ch
                                          isMsaa:msaa != 0];
}

void* mtl_rtt_wrap(void* ctx, void* mtl_texture, int32_t pw, int32_t ph)
{
    return [[MetalRTTexture alloc] createTexture:(MetalContext *)ctx
                                 ofPhysicalWidth:pw
                                ofPhysicalHeight:ph
                                          mtlTex:(long)mtl_texture];
}

// This texure replaceRegion is executed on CPU
void mtl_rtt_init(void* rtt, const int32_t* pixels)
{
    [(MetalRTTexture *)rtt initRTT:(int *)pixels];
}

void mtl_rtt_read_pixels(void* rtt, int32_t* dst)
{
    [(MetalRTTexture *)rtt readPixels:dst];
}

void mtl_rtt_read_pixels_contiguous(void* rtt, int32_t* dst)
{
    [(MetalRTTexture *)rtt readPixelsFromRTT:dst];
}

// ** Shaders **

void* mtl_shader_create(void* ctx, const uint16_t* name_utf16, int32_t name_len)
{
    NSString *nameString = NULL;
    if (name_utf16 != NULL) {
        nameString = [NSString stringWithCharacters:name_utf16 length:name_len];
    }
    return [[MetalShader alloc] initWithContext:(MetalContext *)ctx withFragFunc:nameString];
}

void mtl_shader_enable(void* shader)
{
    [(MetalShader *)shader enable];
}

void mtl_shader_disable(void* shader)
{
    [(MetalShader *)shader disable];
}

void mtl_shader_set_texture(void* shader, int32_t tex_unit, int32_t uniform_id, void* tex,
                            int32_t linear, int32_t wrap_mode)
{
    id<MTLTexture> texture = [(MetalTexture *)tex getTexture];
    [(MetalShader *)shader setTexture:tex_unit
                            uniformID:uniform_id
                              texture:texture
                             isLinear:linear
                             wrapMode:wrap_mode];
}

void mtl_shader_set_int(void* shader, int32_t uniform_id, int32_t i0)
{
    [(MetalShader *)shader setInt:uniform_id i0:i0];
}

void mtl_shader_set_float1(void* shader, int32_t uniform_id, float f0)
{
    [(MetalShader *)shader setFloat1:uniform_id f0:f0];
}

void mtl_shader_set_float2(void* shader, int32_t uniform_id, float f0, float f1)
{
    [(MetalShader *)shader setFloat2:uniform_id f0:f0 f1:f1];
}

void mtl_shader_set_float3(void* shader, int32_t uniform_id, float f0, float f1, float f2)
{
    [(MetalShader *)shader setFloat3:uniform_id f0:f0 f1:f1 f2:f2];
}

void mtl_shader_set_float4(void* shader, int32_t uniform_id, float f0, float f1, float f2, float f3)
{
    [(MetalShader *)shader setFloat4:uniform_id f0:f0 f1:f1 f2:f2 f3:f3];
}

void mtl_shader_set_constants(void* shader, int32_t uniform_id, const float* values, int32_t float_count)
{
    [(MetalShader *)shader setConstants:uniform_id values:(float *)values size:float_count];
}

// ** 3D **

void* mtl_mesh_create(void* ctx)
{
    return [[MetalMesh alloc] createMesh:(MetalContext *)ctx];
}

int32_t mtl_mesh_build_geometry_short(void* mesh, const float* vb, int32_t vb_len, int32_t vb_capacity,
                                      const uint16_t* ib, int32_t ib_len, int32_t ib_capacity)
{
    if (vb_len < 0 || ib_len < 0) {
        return 0;
    }

    unsigned int uvbSize = (unsigned int) vb_len;
    unsigned int uibSize = (unsigned int) ib_len;
    unsigned int vertexBufferSize = (unsigned int) vb_capacity;
    unsigned int indexBufferSize = (unsigned int) ib_capacity;

    if (uvbSize > vertexBufferSize || uibSize > indexBufferSize) {
        return 0;
    }

    if (vb == NULL || ib == NULL) {
        return 0;
    }

    return [(MetalMesh *)mesh buildBuffersShort:(float *)vb
                                          vSize:uvbSize
                                        iBuffer:(unsigned short *)ib
                                          iSize:uibSize];
}

int32_t mtl_mesh_build_geometry_int(void* mesh, const float* vb, int32_t vb_len, int32_t vb_capacity,
                                    const uint32_t* ib, int32_t ib_len, int32_t ib_capacity)
{
    if (vb_len < 0 || ib_len < 0) {
        return 0;
    }

    unsigned int uvbSize = (unsigned int) vb_len;
    unsigned int uibSize = (unsigned int) ib_len;
    unsigned int vertexBufferSize = (unsigned int) vb_capacity;
    unsigned int indexBufferSize = (unsigned int) ib_capacity;

    if (uvbSize > vertexBufferSize || uibSize > indexBufferSize) {
        return 0;
    }

    if (vb == NULL || ib == NULL) {
        return 0;
    }

    return [(MetalMesh *)mesh buildBuffersInt:(float *)vb
                                        vSize:uvbSize
                                      iBuffer:(unsigned int *)ib
                                        iSize:uibSize];
}

void* mtl_phong_material_create(void* ctx)
{
    return [[MetalPhongMaterial alloc] createPhongMaterial:(MetalContext *)ctx];
}

void mtl_phong_material_set_diffuse_color(void* material, float r, float g, float b, float a)
{
    [(MetalPhongMaterial *)material setDiffuseColor:r g:g b:b a:a];
}

void mtl_phong_material_set_specular_color(void* material, int32_t set, float r, float g, float b, float a)
{
    [(MetalPhongMaterial *)material setSpecularColor:set r:r g:g b:b a:a];
}

void mtl_phong_material_set_map(void* material, int32_t map_type, void* tex)
{
    [(MetalPhongMaterial *)material setMap:map_type map:[(MetalTexture *)tex getTexture]];
}

void* mtl_mesh_view_create(void* ctx, void* mesh)
{
    return [[MetalMeshView alloc] createMeshView:(MetalContext *)ctx
                                            mesh:(MetalMesh *)mesh];
}

void mtl_mesh_view_set_culling_mode(void* view, int32_t cull)
{
    int cullMode = cull;
    switch (cull) {
        case MTL_CULL_BACK:
            cullMode = MTLCullModeBack;
            break;
        case MTL_CULL_FRONT:
            cullMode = MTLCullModeFront;
            break;
        case MTL_CULL_NONE:
            cullMode = MTLCullModeNone;
            break;
    }
    [(MetalMeshView *)view setCullingMode:cullMode];
}

void mtl_mesh_view_set_material(void* view, void* material)
{
    [(MetalMeshView *)view setMaterial:(MetalPhongMaterial *)material];
}

void mtl_mesh_view_set_wireframe(void* view, int32_t wireframe)
{
    [(MetalMeshView *)view setWireframe:wireframe];
}

void mtl_mesh_view_set_ambient_light(void* view, float r, float g, float b)
{
    [(MetalMeshView *)view setAmbientLight:r g:g b:b];
}

void mtl_mesh_view_set_light(void* view, int32_t index, float x, float y, float z,
                             float r, float g, float b, float w,
                             float ca, float la, float qa, float is_attenuated, float max_range,
                             float dir_x, float dir_y, float dir_z,
                             float inner_angle, float outer_angle, float falloff)
{
    [(MetalMeshView *)view setLight:index
        x:x y:y z:z
        r:r g:g b:b w:w
        ca:ca la:la qa:qa
        isA:is_attenuated range:max_range
        dirX:dir_x dirY:dir_y dirZ:dir_z
        inA:inner_angle outA:outer_angle
        falloff:falloff];
}

void mtl_mesh_view_render(void* ctx, void* view)
{
    [(MetalContext *)ctx renderMeshView:(MetalMeshView *)view];
}
