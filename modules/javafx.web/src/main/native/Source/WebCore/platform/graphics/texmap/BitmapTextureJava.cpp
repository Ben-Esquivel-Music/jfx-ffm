/*
 * Copyright (c) 2018, 2026, Oracle and/or its affiliates. All rights reserved.
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

#include "config.h"
#include "BitmapTexture.h"

#if USE(TEXTURE_MAPPER)

#include "ClipStack.h"
#include "DestinationColorSpace.h"
#include "FloatRect.h"
#include "GraphicsContext.h"
#include "GraphicsLayer.h"
#include "GraphicsTypes.h"
#include "ImageBuffer.h"
#include "ImagePaintingOptions.h"
#include "IntPoint.h"
#include "IntRect.h"
#include "IntSize.h"
#include "NativeImage.h"
#include "PixelFormat.h"
#include "RenderingMode.h"
#include <utility>
#include <wtf/RefPtr.h>

namespace WebCore {

BitmapTexture::BitmapTexture(const IntSize& size, OptionSet<Flags> flags)
    : m_flags(flags)
    , m_size(size)
{
    m_clipStack.reset(IntRect(IntPoint::zero(), m_size), ClipStack::YAxisMode::Default);
}

BitmapTexture::~BitmapTexture() = default;

// A reset never keeps the buffer. A frame decodes all pending commands of a buffer at the first draw that names
// it, so a pooled texture painted again later in the same frame would show its new content at the earlier draw.
// The old buffer stays alive through the references the queued commands hold.
void BitmapTexture::reset(const IntSize& size, OptionSet<Flags> flags)
{
    m_flags = flags;
    m_size = size;
    m_filterOperation = nullptr;
    m_clipStack.reset(IntRect(IntPoint::zero(), m_size), ClipStack::YAxisMode::Default);
    m_imageBuffer = nullptr;
}

ImageBuffer* BitmapTexture::ensureImageBuffer()
{
    if (!m_imageBuffer) {
        m_imageBuffer = ImageBuffer::create(m_size, RenderingMode::Unaccelerated, RenderingPurpose::Unspecified, 1,
            DestinationColorSpace::SRGB(), PixelFormat::BGRA8);
    }
    return m_imageBuffer.get();
}

RefPtr<ImageBuffer> BitmapTexture::takeImageBuffer()
{
    return std::exchange(m_imageBuffer, nullptr);
}

void BitmapTexture::updateContents(GraphicsLayer* sourceLayer, const IntRect& targetRect, const IntPoint& offset,
    float scale)
{
    IntRect paintRect = intersection(targetRect, IntRect(IntPoint::zero(), m_size));
    if (paintRect.isEmpty())
        return;
    ImageBuffer* buffer = ensureImageBuffer();
    if (!buffer)
        return;

    IntRect sourceRect(offset, targetRect.size());
    sourceRect.scale(1 / scale);

    GraphicsContext& context = buffer->context();
    context.save();
    context.clip(paintRect);
    context.clearRect(paintRect);
    context.translate(targetRect.x(), targetRect.y());
    context.scale(scale);
    context.translate(-sourceRect.x(), -sourceRect.y());
    context.setTextDrawingMode(TextDrawingMode::Fill);
    sourceLayer->paintGraphicsLayerContents(context, sourceRect);
    context.restore();
}

void BitmapTexture::updateContents(NativeImage* frameImage, const IntRect& targetRect, const IntPoint& offset)
{
    if (!frameImage)
        return;
    ImageBuffer* buffer = ensureImageBuffer();
    if (!buffer)
        return;

    GraphicsContext& context = buffer->context();
    context.save();
    context.drawNativeImage(*frameImage, targetRect, FloatRect(offset, targetRect.size()),
        ImagePaintingOptions { CompositeOperator::Copy });
    context.restore();
}

} // namespace WebCore

#endif // USE(TEXTURE_MAPPER)
