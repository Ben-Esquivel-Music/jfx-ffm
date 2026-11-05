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

package test.com.sun.prism.mtl;

import com.sun.prism.PixelFormat;
import com.sun.prism.impl.PrismSettings;
import com.sun.prism.mtl.MTLNativeShim;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Binding tests for {@code com.sun.prism.mtl.MTLNative}, the FFM facade over the {@code mtl_*} C ABI of
 * {@code prism_mtl} ({@code native-prism-mtl/prism_mtl_api.h}): the symbols, the ABI guard, the constants the C
 * defines for itself now that no {@code javac -h} header gives them, and {@code objc_release}. No struct crosses the
 * ABI, so there is no layout to check against {@code sizeof}.
 * <p>
 * Gated like the Metal goldens ({@link MTLNatives}): skipped off macOS and in a build without natives, failed when a
 * build of this module has natives but a {@code prism_mtl} that does not load or bind. Nothing here creates a Metal
 * device or touches AppKit.
 */
public class MTLNativeTest {

    /** Every function {@code prism_mtl_api.h} exports, in header order. */
    static final List<String> EXPORTED_SYMBOLS = List.of(
            "mtl_abi_version", "mtl_abi_constant", "mtl_supports_metal",
            "mtl_context_create", "mtl_context_release", "mtl_context_commit", "mtl_context_draw_indexed_quads",
            "mtl_context_update_render_target", "mtl_context_set_clip_rect", "mtl_context_reset_clip_rect",
            "mtl_context_set_proj_view_matrix", "mtl_context_set_transform", "mtl_context_set_world_transform",
            "mtl_context_set_camera_position", "mtl_context_set_composite_mode", "mtl_context_is_current_rtt",
            "mtl_context_blit", "mtl_context_get_command_queue", "mtl_context_clear",
            "mtl_texture_create", "mtl_texture_update", "mtl_texture_update_yuv422",
            "mtl_rtt_create", "mtl_rtt_wrap", "mtl_rtt_init", "mtl_rtt_read_pixels",
            "mtl_rtt_read_pixels_contiguous",
            "mtl_shader_create", "mtl_shader_uniform_count", "mtl_shader_uniform_table", "mtl_shader_enable",
            "mtl_shader_disable", "mtl_shader_set_texture", "mtl_shader_set_int", "mtl_shader_set_float1",
            "mtl_shader_set_float2", "mtl_shader_set_float3", "mtl_shader_set_float4", "mtl_shader_set_constants",
            "mtl_mesh_create", "mtl_mesh_build_geometry_short", "mtl_mesh_build_geometry_int",
            "mtl_phong_material_create", "mtl_phong_material_set_diffuse_color",
            "mtl_phong_material_set_specular_color", "mtl_phong_material_set_map", "mtl_mesh_view_create",
            "mtl_mesh_view_set_culling_mode", "mtl_mesh_view_set_material", "mtl_mesh_view_set_wireframe",
            "mtl_mesh_view_set_ambient_light", "mtl_mesh_view_set_light", "mtl_mesh_view_render");

    private static final Path HEADER = Path.of("src/main/native-prism-mtl/prism_mtl_api.h");

    /* The return type may not cross a '(' or a '"', so the #define of PRISM_MTL_EXPORT itself never matches. */
    private static final Pattern PROTOTYPE = Pattern.compile(
            "PRISM_MTL_EXPORT\\s+([\\w\\s*]+?)\\b(mtl_\\w+)\\s*\\(([^)]*)\\)\\s*;");

    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private static final String MTL_CONTEXT = "com.sun.prism.mtl.MTLContext";

    /* Indices of enum MtlAbiConstant. */
    private static final int ABI_COMPMODE_CLEAR = 0;
    private static final int ABI_CULL_BACK = 5;
    private static final int ABI_SIZEOF_PRISM_SOURCE_VERTEX = 8;
    private static final int ABI_NUM_OF_FLOATS_PER_VERTEX = 9;
    private static final int ABI_MAX_NUM_QUADS = 10;
    private static final int ABI_MAX_NUM_LIGHTS = 11;
    private static final int ABI_PFORMAT_INT_ARGB_PRE = 12;
    private static final int ABI_CONSTANT_COUNT = 21;

    @BeforeAll
    static void requireNatives() {
        MTLNatives.require();
        MTLNativeShim.loadLibrary();
    }

    @Test
    public void theTestListsExactlyTheExportsOfTheHeader() throws IOException {
        assertEquals(53, EXPORTED_SYMBOLS.size());
        assertEquals(EXPORTED_SYMBOLS, List.copyOf(headerPrototypes().keySet()));
    }

    @Test
    public void theFacadeBindsEveryExportInHeaderOrder() {
        assertEquals(EXPORTED_SYMBOLS, MTLNativeShim.boundSymbols());
        assertEquals(List.of(), MTLNativeShim.missingSymbols());
    }

    @Test
    public void everyDescriptorHasTheCarriersOfItsPrototype() throws IOException {
        Map<String, MethodType> expected = headerPrototypes();
        Map<String, MethodType> bound = MTLNativeShim.boundCarrierTypes();
        for (String name : EXPORTED_SYMBOLS) {
            assertEquals(expected.get(name), bound.get(name), name);
        }
    }

    @Test
    public void everyExportResolvesInTheLoadedLibrary() {
        SymbolLookup lookup = SymbolLookup.loaderLookup();
        for (String name : EXPORTED_SYMBOLS) {
            assertTrue(lookup.find(name).isPresent(), "prism_mtl does not export " + name);
        }
    }

    @Test
    public void onlyTheShortNonBlockingConstantsCopyIsCritical() {
        assertEquals(List.of("mtl_shader_set_constants"), MTLNativeShim.criticalSymbols());
    }

    @Test
    public void theLibraryHasTheAbiVersionTheFacadeWasWrittenFor() {
        assertEquals(1, MTLNativeShim.expectedAbiVersion());
        assertEquals(MTLNativeShim.expectedAbiVersion(), MTLNativeShim.abiVersion());
    }

    @Test
    public void compositeModesAreThoseOfMTLContext() {
        List<String> fields = List.of("MTL_COMPMODE_CLEAR", "MTL_COMPMODE_SRC", "MTL_COMPMODE_SRCOVER",
                "MTL_COMPMODE_DSTOUT", "MTL_COMPMODE_ADD");
        for (int i = 0; i < fields.size(); i++) {
            assertEquals(MTLNativeShim.staticInt(MTL_CONTEXT, fields.get(i)),
                    MTLNativeShim.abiConstant(ABI_COMPMODE_CLEAR + i), fields.get(i));
        }
    }

    @Test
    public void cullModesAreThoseOfMTLContext() {
        List<String> fields = List.of("CULL_BACK", "CULL_FRONT", "CULL_NONE");
        for (int i = 0; i < fields.size(); i++) {
            assertEquals(MTLNativeShim.staticInt(MTL_CONTEXT, fields.get(i)),
                    MTLNativeShim.abiConstant(ABI_CULL_BACK + i), fields.get(i));
        }
    }

    @Test
    public void aQuadVertexIsTheVertexBufferStride() {
        int floatsPerVertex = MTLNativeShim.staticInt("com.sun.prism.impl.VertexBuffer", "FLOATS_PER_VERT");
        int colorBytesPerVertex = MTLNativeShim.staticInt("com.sun.prism.impl.VertexBuffer", "BYTES_PER_VERT");
        assertEquals(28, MTLNativeShim.abiConstant(ABI_SIZEOF_PRISM_SOURCE_VERTEX));
        assertEquals(Float.BYTES * floatsPerVertex, MTLNativeShim.abiConstant(ABI_SIZEOF_PRISM_SOURCE_VERTEX));
        assertEquals(floatsPerVertex, MTLNativeShim.quadVertexFloats());
        assertEquals(colorBytesPerVertex, MTLNativeShim.quadVertexColorBytes());
    }

    @Test
    public void aMeshVertexIsTheBaseMeshVertexSize() {
        assertEquals(MTLNativeShim.staticInt("com.sun.prism.impl.BaseMesh", "VERTEX_SIZE_VB"),
                MTLNativeShim.abiConstant(ABI_NUM_OF_FLOATS_PER_VERTEX));
    }

    @Test
    public void oneIndexBufferPassCoversTheLargestQuadBatch() {
        int maxQuads = MTLNativeShim.abiConstant(ABI_MAX_NUM_QUADS);
        assertEquals(4096, maxQuads);
        assertEquals(PrismSettings.superShader ? maxQuads : 256, MTLNativeShim.numQuads());
    }

    @Test
    public void theLightSlotsAreTheIndicesMTLMeshViewAccepts() {
        // MTLMeshView.setLight forwards an index only when 0 <= index <= 2; no Java constant names the count.
        assertEquals(3, MTLNativeShim.abiConstant(ABI_MAX_NUM_LIGHTS));
    }

    @Test
    public void pixelFormatsAreTheOrdinalsTheAuditLists() {
        int[] expected = {
            PixelFormat.INT_ARGB_PRE.ordinal(),
            PixelFormat.BYTE_BGRA_PRE.ordinal(),
            PixelFormat.values().length,
            PixelFormat.BYTE_RGB.ordinal(),
            PixelFormat.BYTE_GRAY.ordinal(),
            PixelFormat.BYTE_ALPHA.ordinal(),
            PixelFormat.MULTI_YCbCr_420.ordinal(),
            PixelFormat.BYTE_APPLE_422.ordinal(),
            PixelFormat.FLOAT_XYZW.ordinal()
        };
        // PFORMAT_BYTE_RGBA_PRE is the ordinal of BYTE_BGRA_PRE, and PFORMAT_BYTE_BGRA_PRE (8) is no ordinal at all:
        // a BYTE_BGRA_PRE texture takes the default branch of createTexture, which is BGRA8 as well.
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], MTLNativeShim.abiConstant(ABI_PFORMAT_INT_ARGB_PRE + i), "PFORMAT index " + i);
        }
        assertEquals(ABI_CONSTANT_COUNT, ABI_PFORMAT_INT_ARGB_PRE + expected.length);
    }

    @Test
    public void indicesOutsideTheConstantTableAnswerMinusOne() {
        for (int index : new int[] {Integer.MIN_VALUE, -1, ABI_CONSTANT_COUNT, Integer.MAX_VALUE}) {
            assertEquals(-1, MTLNativeShim.abiConstant(index), "index " + index);
        }
    }

    @Test
    public void objcReleaseResolvesInLibobjcAndIgnoresNull() {
        assertEquals("/usr/lib/libobjc.A.dylib", MTLNativeShim.libobjc());
        assertNotEquals(0L, MTLNativeShim.objcReleaseAddress());
        MTLNativeShim.objcRelease(0L);
    }

    @Test
    public void aDirectBufferIsPassedFromItsBaseWhateverItsPosition() {
        ByteBuffer metallib = ByteBuffer.allocateDirect(64);
        long base = MemorySegment.ofBuffer(metallib).address();
        metallib.put(new byte[64]);
        MemorySegment passed = MTLNativeShim.directBase(metallib);
        assertEquals(base, passed.address());
        assertEquals(64, passed.byteSize());
        assertEquals(64, metallib.position());
    }

    @Test
    public void aDirectSliceIsPassedFromItsOwnStart() {
        ByteBuffer whole = ByteBuffer.allocateDirect(64);
        long base = MemorySegment.ofBuffer(whole).address();
        ByteBuffer slice = whole.position(16).slice();
        IntBuffer ints = slice.asIntBuffer().position(3);
        MemorySegment passed = MTLNativeShim.directBase(ints);
        assertEquals(base + 16, passed.address());
        assertEquals(48, passed.byteSize());
    }

    @Test
    public void aHeapBufferIsPassedAsNull() {
        assertEquals(MemorySegment.NULL, MTLNativeShim.directBase(IntBuffer.allocate(4)));
        assertEquals(MemorySegment.NULL, MTLNativeShim.directBase(ByteBuffer.allocate(4).asReadOnlyBuffer()));
        assertEquals(MemorySegment.NULL, MTLNativeShim.directBase(null));
    }

    /** Every {@code PRISM_MTL_EXPORT} prototype of the header, as the Java carrier types it must be bound with. */
    private static Map<String, MethodType> headerPrototypes() throws IOException {
        assertTrue(Files.isRegularFile(HEADER), "run from modules/javafx.graphics: no " + HEADER.toAbsolutePath());
        String header = COMMENT.matcher(Files.readString(HEADER)).replaceAll(" ");
        Map<String, MethodType> prototypes = new LinkedHashMap<>();
        Matcher prototype = PROTOTYPE.matcher(header);
        while (prototype.find()) {
            List<Class<?>> parameters = new ArrayList<>();
            String list = prototype.group(3).trim();
            if (!list.equals("void")) {
                for (String parameter : list.split(",")) {
                    parameters.add(carrier(parameter.trim().replaceFirst("\\w+$", "")));
                }
            }
            prototypes.put(prototype.group(2), MethodType.methodType(carrier(prototype.group(1)), parameters));
        }
        return prototypes;
    }

    /** Any pointer is an {@code ADDRESS}; {@code uint32_t} has the bits of a {@code JAVA_INT}. */
    private static Class<?> carrier(String cType) {
        String type = cType.replace("const", "").trim();
        if (type.endsWith("*")) {
            return MemorySegment.class;
        }
        return switch (type) {
            case "void" -> void.class;
            case "int32_t", "uint32_t" -> int.class;
            case "int64_t" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            default -> throw new AssertionError("no carrier for the C type " + cType);
        };
    }
}
