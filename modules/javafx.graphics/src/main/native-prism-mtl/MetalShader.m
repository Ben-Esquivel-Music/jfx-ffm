/*
 * Copyright (c) 2021, 2026, Oracle and/or its affiliates. All rights reserved.
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

#import "PrismShaderCommon.h"
#import "DecoraShaderCommon.h"
#import "MetalShader.h"
#import "prism_mtl_api.h"

@implementation MetalShader

- (id) initWithContext:(MetalContext*)ctx
          withFragFunc:(NSString*)fragName
{
    self = [super init];
    if (self) {
        context = ctx;
        argsUpdated = false;
        @autoreleasepool {
            fragTexArgsDict    = [[[NSMutableDictionary alloc] init] retain];
            fragTexSamplerDict = [[[NSMutableDictionary alloc] init] retain];
            pipeStateNonMSAANoDepthDict = [[[NSMutableDictionary alloc] init] retain];
            pipeStateNonMSAADepthDict = [[[NSMutableDictionary alloc] init] retain];
            pipeStateMSAANoDepthDict = [[[NSMutableDictionary alloc] init] retain];
            pipeStateMSAADepthDict = [[[NSMutableDictionary alloc] init] retain];
            fragArgIndicesDict = [getPRISMDict(fragName) retain];
            if (fragArgIndicesDict == nil) {
                fragArgIndicesDict = [getDECORADict(fragName) retain];
            }
        }
        currentRingBufferOffset = -1;
        fragFuncName = fragName;
        fragmentFunction = [[context getPipelineManager] getFunction:fragFuncName];
        NSString* key = (NSString*)[[fragArgIndicesDict allKeys] objectAtIndex:0];
        if ([fragArgIndicesDict count] == 1 && [key isEqualToString:@"UNUSED"]) {
            argumentBufferLength = 0;
        } else {
            argumentEncoder = [fragmentFunction newArgumentEncoderWithBufferIndex:0];
            argumentBufferLength = argumentEncoder.encodedLength;
            argumentBuffer = [[context getDevice] newBufferWithLength:argumentBufferLength options:0];
            argumentBuffer.label = [NSString stringWithFormat:@"JFX Argument Buffer for fragmentFunction %@", fragFuncName];
            [argumentEncoder setArgumentBuffer:argumentBuffer offset:0];
        }
    }
    return self;
}

- (void) setArgsUpdated:(bool)updated
{
    argsUpdated = updated;
}

- (void) enable
{
    [context setCurrentShader:self];
}

- (void) disable
{
    [context setCurrentShader:nil];
}

- (id<MTLRenderPipelineState>) getPipelineState:(bool)isMSAA
                                  compositeMode:(int)compositeMode;
{
    NSMutableDictionary *psDict;
    if (isMSAA) {
        if ([context isDepthEnabled]) {
            psDict = pipeStateMSAADepthDict;
        } else {
            psDict = pipeStateMSAANoDepthDict;
        }
    } else {
        if ([context isDepthEnabled]) {
            psDict = pipeStateNonMSAADepthDict;
        } else {
            psDict = pipeStateNonMSAANoDepthDict;
        }
    }
    NSNumber *keyCompMode = [NSNumber numberWithInt:compositeMode];
    id<MTLRenderPipelineState> pipeState = psDict[keyCompMode];
    if (pipeState == nil) {
        pipeState = [[context getPipelineManager] getPipeStateWithFragFunc:fragmentFunction
                                                             compositeMode:compositeMode];
        [psDict setObject:pipeState forKey:keyCompMode];
    }
    return pipeState;
}

- (NSUInteger) getArgumentBufferLength
{
    return argumentBufferLength;
}

- (int) getRingBufferOffset
{
    return currentRingBufferOffset;
}

- (id<MTLBuffer>) getRingBuffer
{
    return argumentBufferForCB;
}

- (void) copyArgBufferToRingBuffer
{
    if (argumentBufferLength != 0 && argsUpdated) {
        currentRingBufferOffset = [[context getArgsRingBuffer] reserveBytes:argumentBufferLength];

        if (currentRingBufferOffset < 0) {
            currentRingBufferOffset = 0;
            argumentBufferForCB = [context getTransientBufferWithBytes:argumentBuffer.contents
                                                                length:argumentBufferLength];
        } else {
            argumentBufferForCB = [[context getArgsRingBuffer] getBuffer];
            memcpy(argumentBufferForCB.contents + currentRingBufferOffset,
                                argumentBuffer.contents, argumentBufferLength);
            argsUpdated = false;
        }
    }
}

- (NSMutableDictionary*) getTexutresDict
{
    return fragTexArgsDict;
}

- (NSMutableDictionary*) getSamplersDict
{
    return fragTexSamplerDict;
}

- (NSDictionary*) getFragArgIndicesDict
{
    return fragArgIndicesDict;
}

- (NSUInteger) getArgumentID:(NSString*)name
{
    return 0;
}

- (void) setInt:(int)uniformID i0:(int)i0
{
    argsUpdated = true;
    int *anIntPtr = [argumentEncoder constantDataAtIndex:uniformID];
    *anIntPtr = i0;
}


- (void) setTexture:(int)texID
          uniformID:(int)uniformID
            texture:(id<MTLTexture>)texture
           isLinear:(bool)isLinear
           wrapMode:(int)wrapMode
{
    argsUpdated = true;
    NSNumber *idNum = [NSNumber numberWithInt:uniformID];
    [fragTexArgsDict setObject:texture forKey:idNum];

    [argumentEncoder setTexture:texture atIndex:uniformID];

    id<MTLSamplerState> sampler = [context getSampler:isLinear wrapMode:wrapMode];
    [fragTexSamplerDict setObject:sampler forKey:[NSNumber numberWithInt:texID]];
}

- (void) setFloat1:(int)uniformID f0:(float)f0
{
    argsUpdated = true;
    float *aFloatPtr = [argumentEncoder constantDataAtIndex:uniformID];
    *aFloatPtr = f0;
}

- (void) setFloat2:(int)uniformID f0:(float)f0 f1:(float)f1
{
    argsUpdated = true;
    float *aFloatPtr = [argumentEncoder constantDataAtIndex:uniformID];
    *aFloatPtr++ = f0;
    *aFloatPtr = f1;
}

- (void) setFloat3:(int)uniformID f0:(float)f0 f1:(float)f1 f2:(float)f2
{
    argsUpdated = true;
    float *aFloatPtr = [argumentEncoder constantDataAtIndex:uniformID];
    *aFloatPtr++ = f0;
    *aFloatPtr++ = f1;
    *aFloatPtr = f2;
}

- (void) setFloat4:(int)uniformID f0:(float)f0 f1:(float)f1 f2:(float)f2  f3:(float)f3
{
    argsUpdated = true;
    float *aFloatPtr = [argumentEncoder constantDataAtIndex:uniformID];
    *aFloatPtr++ = f0;
    *aFloatPtr++ = f1;
    *aFloatPtr++ = f2;
    *aFloatPtr = f3;
}

- (void) setConstants:(int)uniformID values:(float[])values size:(int)size
{
    argsUpdated = true;
    float *aFloatPtr = [argumentEncoder constantDataAtIndex:uniformID];
    memcpy(aFloatPtr, values, size * 4);
}

- (void) dealloc
{
    for (NSNumber *keyPipeState in pipeStateNonMSAANoDepthDict) {
        [pipeStateNonMSAANoDepthDict[keyPipeState] release];
    }
    for (NSNumber *keyPipeState in pipeStateNonMSAADepthDict) {
        [pipeStateNonMSAADepthDict[keyPipeState] release];
    }
    for (NSNumber *keyPipeState in pipeStateMSAANoDepthDict) {
        [pipeStateMSAANoDepthDict[keyPipeState] release];
    }
    for (NSNumber *keyPipeState in pipeStateMSAADepthDict) {
        [pipeStateMSAADepthDict[keyPipeState] release];
    }
    for (NSNumber *keyTexArg in fragTexArgsDict) {
        [fragTexArgsDict[keyTexArg] release];
    }
    for (NSNumber *keyTexSampler in fragTexSamplerDict) {
        [fragTexSamplerDict[keyTexSampler] release];
    }
    for (NSNumber *keyArgIndex in fragArgIndicesDict) {
        [fragArgIndicesDict[keyArgIndex] release];
    }
    [pipeStateNonMSAANoDepthDict release];
    [pipeStateNonMSAADepthDict release];
    [pipeStateMSAANoDepthDict release];
    [pipeStateMSAADepthDict release];
    [fragTexArgsDict release];
    [fragTexSamplerDict release];
    [fragArgIndicesDict release];
    [super dealloc];
}

@end // MetalShader

int32_t mtl_shader_uniform_count(void* shader)
{
    return (int32_t) [[(MetalShader *)shader getFragArgIndicesDict] count];
}

int32_t mtl_shader_uniform_table(void* shader, int32_t* ids, int32_t ids_cap, char* names, int32_t names_cap)
{
    NSDictionary *dict = [(MetalShader *)shader getFragArgIndicesDict];
    int32_t entry = 0;
    int32_t namesSize = 0;
    for (NSString *key in dict) {
        NSNumber *index = dict[key];
        const char *name = [key UTF8String];
        int32_t nameSize = (int32_t) strlen(name) + 1;
        if (ids != NULL && entry < ids_cap) {
            ids[entry] = index.intValue;
        }
        if (names != NULL && namesSize + nameSize <= names_cap) {
            memcpy(names + namesSize, name, nameSize);
        }
        namesSize += nameSize;
        entry++;
    }
    return namesSize;
}
